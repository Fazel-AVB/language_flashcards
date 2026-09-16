import pytest

from flashcards.session import build_deck, pick_direction
from flashcards.workbook import load_cards


@pytest.fixture
def cards(workbook_path):
    return load_cards(str(workbook_path)).cards


def test_mode_mix_takes_everything(cards):
    assert len(build_deck(cards, mode="mix")) == len(cards)


def test_mode_topic_filters(cards):
    deck = build_deck(cards, mode="topic", topics=["Verbs"])
    assert [c.german for c in deck] == ["werden"]


def test_mode_difficult_excludes_ungraded(cards):
    # threshold 2: Stift(1) qualifies; werden(None) does not; riesig clamped to 5 does not.
    deck = build_deck(cards, mode="difficult", topics=None, difficult_threshold=2)
    assert [c.german for c in deck] == ["Stift"]


def test_order_lowest_confidence_first(cards):
    deck = build_deck(cards, mode="mix", order="lowest_confidence_first", seed=1)
    # werden (ungraded -> treated as -1) first, then Stift(1), Bericht(3), riesig(5)
    assert [c.german for c in deck] == ["werden", "Stift", "Bericht", "riesig"]


def test_order_oldest_first(cards):
    deck = build_deck(cards, mode="mix", order="oldest_first", seed=1)
    # Bericht is 2026-09-05; the other three are 2026-09-03 and keep workbook order.
    assert deck[-1].german == "Bericht"


def test_order_random_is_seed_stable(cards):
    a = [c.id for c in build_deck(cards, mode="mix", order="random", seed=7)]
    b = [c.id for c in build_deck(cards, mode="mix", order="random", seed=7)]
    assert a == b


def test_pick_direction():
    assert pick_direction("de_en", "X!2") == "de_en"
    assert pick_direction("en_de", "X!2") == "en_de"
    # mixed: stable for a given (seed, card), and not all cards identical
    d1 = pick_direction("mixed", "S!2", seed=3)
    assert d1 == pick_direction("mixed", "S!2", seed=3)
    variety = {pick_direction("mixed", f"S!{i}", seed=3) for i in range(20)}
    assert variety == {"de_en", "en_de"}


def test_unknown_mode_and_order(cards):
    with pytest.raises(ValueError):
        build_deck(cards, mode="bogus")
    with pytest.raises(ValueError):
        build_deck(cards, mode="mix", order="bogus")
