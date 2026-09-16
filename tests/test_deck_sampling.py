"""Session cap + score-weighted drawing, including the adaptive repeat picker.

These lock in the *statistical* behaviour, not one fixed deck: the whole point is
that the draw is random but biased toward weak words. Each statistical assertion
runs many seeds and checks a margin wide enough not to flake, so a failure here
means the weighting really changed, not that a die rolled badly.
"""

from collections import Counter
from dataclasses import replace

import pytest

from flashcards.session import (
    WEIGHT_EXPONENT,
    build_deck,
    pick_next_repeat,
    selection_weight,
)


@pytest.fixture
def graded(workbook_path):
    """13 synthetic cards spanning the whole 1..5 range, plus one ungraded."""
    from flashcards.workbook import load_cards

    base = load_cards(str(workbook_path)).cards[0]
    scores = [1, 1, 1, 2, 2, 3, 3, 4, 4, 5, 5, 5, None]
    return [
        replace(base, german=f"w{i}", row=i + 2, confidence=None if s is None else float(s))
        for i, s in enumerate(scores)
    ]


# --------------------------------------------------------------------------- #
# weights
# --------------------------------------------------------------------------- #

def test_weight_decreases_with_confidence():
    weights = [selection_weight(float(s)) for s in (1, 2, 3, 4, 5)]
    assert weights == sorted(weights, reverse=True)


def test_ungraded_outranks_every_graded_word():
    assert selection_weight(None) > selection_weight(1.0)


def test_weak_to_strong_ratio_is_the_documented_one():
    # (5.5 - 1)**2 / (5.5 - 5)**2 == 81. If this changes, the README and the
    # "81x" help text in app.py are wrong too.
    assert selection_weight(1.0) / selection_weight(5.0) == pytest.approx(81.0)
    assert WEIGHT_EXPONENT == 2.0


# --------------------------------------------------------------------------- #
# the session cap
# --------------------------------------------------------------------------- #

def test_limit_caps_unique_words(graded):
    deck = build_deck(graded, mode="mix", limit=5, seed=1)
    assert len(deck) == 5
    assert len({c.id for c in deck}) == 5  # no word drawn twice


def test_limit_above_pool_returns_everything(graded):
    assert len(build_deck(graded, mode="mix", limit=999, seed=1)) == len(graded)


def test_no_limit_returns_everything(graded):
    assert len(build_deck(graded, mode="mix", seed=1)) == len(graded)


def test_limit_must_be_positive(graded):
    with pytest.raises(ValueError, match="limit must be"):
        build_deck(graded, mode="mix", limit=0)


def test_weighted_deck_is_seed_stable(graded):
    a = [c.id for c in build_deck(graded, mode="mix", order="weighted", limit=5, seed=11)]
    b = [c.id for c in build_deck(graded, mode="mix", order="weighted", limit=5, seed=11)]
    assert a == b


def test_weighted_draw_favours_weak_words(graded):
    """A capped weighted deck must be dominated by the weakest words."""
    picked = Counter()
    for seed in range(400):
        for card in build_deck(graded, mode="mix", order="weighted", limit=4, seed=seed):
            picked[card.confidence] += 1

    weak = picked[1.0] + picked[None]        # 4 of the 13 cards
    strong = picked[5.0]                     # 3 of the 13 cards
    assert weak > 3 * strong, f"weak={weak} strong={strong}"


def test_weighted_draw_still_reaches_mastered_words(graded):
    """Biased, not exclusive: a 5 must surface sometimes or it is never revised."""
    seen = any(
        c.confidence == 5.0
        for seed in range(200)
        for c in build_deck(graded, mode="mix", order="weighted", limit=4, seed=seed)
    )
    assert seen


# --------------------------------------------------------------------------- #
# adaptive repeats
# --------------------------------------------------------------------------- #

def test_repeat_picker_skips_the_card_just_finished():
    scores = {"a": 1.0, "b": 4.0}
    assert pick_next_repeat(scores, exclude="a", seed=1, draw=1) == "b"


def test_repeat_picker_returns_the_last_card_even_if_excluded():
    # One word left and it is the one just graded: show it again rather than
    # ending the session with it still below 5.
    assert pick_next_repeat({"a": 2.0}, exclude="a", seed=1) == "a"


def test_repeat_picker_empty_is_none():
    assert pick_next_repeat({}, seed=1) is None


def test_improving_a_word_hands_repeats_to_the_others():
    """The behaviour asked for: as a word's score rises it yields its turn.

    Same three words, same seeds -- only 'a' improves from 1.0 to 4.5. Its share
    of the next 300 draws must fall sharply.
    """
    def share(score_a: float) -> float:
        scores = {"a": score_a, "b": 1.0, "c": 1.0}
        hits = sum(pick_next_repeat(scores, seed=7, draw=d) == "a" for d in range(300))
        return hits / 300

    before, after = share(1.0), share(4.5)
    assert before > 0.28                  # roughly its 1/3 fair share while weak
    assert after < before / 3, f"before={before:.3f} after={after:.3f}"
