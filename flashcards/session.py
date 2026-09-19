"""Deck building: turn the full card list into an ordered review queue.

Pure functions only -- no Streamlit, no I/O -- so the review logic is testable in
isolation. The app layer owns session state (which card is showing, what's been
graded); this module just decides *which cards* and *in what order*.
"""

from __future__ import annotations

import random
from datetime import date

from .models import CONFIDENCE_MAX, Card

MODES = ("topic", "mix", "difficult")
ORDERS = ("weighted", "lowest_confidence_first", "random", "oldest_first")

# Direction is named by ROLE, not by language code: one app serves several
# language pairs (German/English, English/Persian, ...), so "de_en" could not
# mean anything in a Persian workbook. "term" is the word being learned
# (column A), "native" its gloss (column B). See models.LanguagePair.
DIRECTIONS = ("mixed", "term_to_native", "native_to_term")

# Sampling weight = (5.5 - score) ** 2, so score 1 is 81x likelier to be drawn
# than score 5, and an ungraded word outranks everything.
#
# The exponent is NOT decoration. With a linear weight and a realistic library
# (say 30 weak words at score 1 among 300 mostly-mastered ones) the sheer count
# of strong words wins: weak words take only ~33% of the draws, i.e. 7 of a
# 20-card deck. Squaring flips that to ~69%, i.e. 14 of 20, which is what
# "weakest words come up most" actually has to mean. Raise it to sharpen further.
WEIGHT_BASE_MAX = CONFIDENCE_MAX + 0.5
WEIGHT_EXPONENT = 2.0


def selection_weight(confidence: float | None) -> float:
    """How strongly a card should be favoured when drawing a deck.

    Monotonically decreasing in confidence: the weaker the word, the likelier it
    is drawn, and an ungraded word (never seen) outranks every graded one.
    """
    base = WEIGHT_BASE_MAX if confidence is None else WEIGHT_BASE_MAX - confidence
    return base ** WEIGHT_EXPONENT


def build_deck(
    cards: list[Card],
    *,
    mode: str,
    topics: list[str] | None = None,
    difficult_threshold: int = 2,
    order: str = "weighted",
    seed: int | None = None,
    limit: int | None = None,
) -> list[Card]:
    """Filter and order the cards for one review session.

    mode:
      "topic"     -> only the sheets in `topics`
      "mix"       -> every topic
      "difficult" -> only cards with confidence <= difficult_threshold
                     (ungraded cards are excluded -- there is no score to be
                     "difficult" yet); still honours `topics` if given

    `limit` caps the deck at that many unique words. It is applied AFTER ordering,
    so with order="weighted" the truncation *is* the weighted draw: the keys in
    `_order` make taking the first `limit` a weighted sample without replacement.
    """
    if mode not in MODES:
        raise ValueError(f"unknown mode: {mode!r}")
    if order not in ORDERS:
        raise ValueError(f"unknown order: {order!r}")
    if limit is not None and limit < 1:
        raise ValueError(f"limit must be >= 1, got {limit}")

    selected = list(cards)

    if topics and mode in ("topic", "difficult"):
        wanted = set(topics)
        selected = [c for c in selected if c.topic in wanted]

    if mode == "difficult":
        selected = [
            c for c in selected
            if c.confidence is not None and c.confidence <= difficult_threshold
        ]

    ordered = _order(selected, order, seed)
    return ordered if limit is None else ordered[:limit]


def _order(cards: list[Card], order: str, seed: int | None) -> list[Card]:
    rng = random.Random(seed)
    if order == "weighted":
        # Efraimidis-Spirakis: key = U ** (1 / w). Taking the k largest keys is
        # exactly a weighted sample without replacement, and sorting by key gives
        # the weighted ordering for free -- so one pass serves both `limit` and
        # the "weakest words first, but not deterministically" requirement.
        # Do not swap this for random.choices: that samples WITH replacement and
        # would put the same word in a deck twice.
        keys = {c.id: rng.random() ** (1.0 / selection_weight(c.confidence)) for c in cards}
        return sorted(cards, key=lambda c: keys[c.id], reverse=True)

    if order == "random":
        shuffled = list(cards)
        rng.shuffle(shuffled)
        return shuffled

    if order == "oldest_first":
        # Undated cards sort last; ties keep workbook order (stable sort).
        return sorted(cards, key=lambda c: (c.date_added is None, c.date_added or date.max))

    # lowest_confidence_first: ungraded (None) treated as most in need -> sort first.
    # Small random tiebreak so equal-confidence cards aren't always in the same order.
    jitter = {c.id: rng.random() for c in cards}
    return sorted(
        cards,
        key=lambda c: (c.confidence if c.confidence is not None else -1, jitter[c.id]),
    )


def pick_next_repeat(
    scores: dict[str, float | None],
    *,
    exclude: str | None = None,
    seed: int | None = None,
    draw: int = 0,
) -> str | None:
    """Choose the next card to re-drill, favouring whichever is weakest *now*.

    `scores` maps card id -> its current in-session score, not the on-disk value.
    That is the point: when a word improves its weight drops immediately, so the
    words that did not improve take over the remaining repeats.

    `exclude` is the card just finished; it is skipped unless it is the only one
    left, so a word is never shown twice in a row. `draw` makes successive picks
    differ under a fixed `seed` while staying reproducible.
    """
    if not scores:
        return None
    ids = [i for i in scores if i != exclude] or list(scores)
    rng = random.Random(f"{seed}:repeat:{draw}")
    weights = [selection_weight(scores[i]) for i in ids]
    return rng.choices(ids, weights=weights, k=1)[0]


def pick_direction(pref: str, card_id: str, seed: int | None = None) -> str:
    """Resolve the front/back direction for one card.

    Returns "term_to_native" (show the word, recall the meaning) or
    "native_to_term" (the reverse). "mixed" -> deterministic per (seed, card) so a
    card keeps its direction until it is graded and re-drawn, but different cards
    vary.
    """
    if pref in ("term_to_native", "native_to_term"):
        return pref
    # String seed: a tuple seed is deprecated (hashing-based) since Python 3.9.
    rng = random.Random(f"{seed}:{card_id}")
    return "term_to_native" if rng.random() < 0.5 else "native_to_term"
