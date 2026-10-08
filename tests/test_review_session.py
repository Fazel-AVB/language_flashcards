"""The review loop (session.ReviewSession) and the "due" deck mode."""

from dataclasses import replace
from datetime import datetime, timedelta, timezone

import pytest

from flashcards import srs
from flashcards.session import ReviewSession, build_deck
from flashcards.workbook import load_cards

NOW = datetime(2026, 10, 2, 9, 0, tzinfo=timezone.utc)


@pytest.fixture
def cards(workbook_path):
    return load_cards(str(workbook_path)).cards


def session(deck, **kw):
    return ReviewSession(deck, seed=1, scheduler=srs.make_scheduler(fuzz=False), **kw)


def test_first_pass_shows_every_word_once(cards):
    s = session(cards)
    seen = []
    while not s.finished:
        seen.append(s.current)
        s.grade(3, NOW)
    assert seen == [c.id for c in cards]


def test_repeats_compound_until_mastered(cards):
    stift = next(c for c in cards if c.term == "Stift")  # confidence 1
    s = session([stift], repeat=True)
    trace = []
    while not s.finished:
        s.grade(5, NOW)
        trace.append(s.effective_confidence(stift.id))
    assert trace == [3.0, 4.0, 4.5, 5.0]


def test_fsrs_sees_first_grade_and_due_repeats_only(cards):
    stift = next(c for c in cards if c.term == "Stift")
    s = session([stift], repeat=True)
    s.grade(1, NOW)                            # Again -> learning step, due in 1 min
    after_first = s.schedules[stift.id]
    assert after_first.state == "Learning" and after_first.due == NOW + timedelta(minutes=1)

    # Repeat 30 s later: not due for FSRS yet, so only confidence moves.
    assert s.current == stift.id
    assert s.preview(stift.id, 5, NOW + timedelta(seconds=30))[1] is None
    s.grade(5, NOW + timedelta(seconds=30))
    assert s.schedules[stift.id] == after_first
    assert s.log[-1].scheduled is False and s.effective_confidence(stift.id) == 3.0

    # Two minutes later the learning step is due: FSRS takes this grade.
    s.grade(3, NOW + timedelta(minutes=2))
    assert s.log[-1].scheduled is True
    assert s.schedules[stift.id] != after_first


def test_cell_updates(cards):
    stift = next(c for c in cards if c.term == "Stift")   # confidence 1
    bericht = next(c for c in cards if c.term == "Bericht")  # confidence 3
    s = session([stift, bericht])
    s.grade(5, NOW)
    s.grade(3, NOW)  # average lands back on 3: no confidence change
    up = s.cell_updates()
    assert up[(stift.sheet, stift.row)]["L"] == 3.0
    assert "L" not in up[(bericht.sheet, bericht.row)]
    assert set(srs.SRS_COLUMNS) <= set(up[(bericht.sheet, bericht.row)])


def test_skip_and_end(cards):
    s = session(cards)
    s.skip()
    assert s.cell_updates() == {}
    s.end()
    assert s.finished
    with pytest.raises(ValueError):
        s.grade(3, NOW)


def test_empty_deck_rejected():
    with pytest.raises(ValueError):
        ReviewSession([])


def test_due_mode_orders_due_then_new_and_hides_later(cards):
    by_term = {c.term: c for c in cards}
    sched = srs.make_scheduler(fuzz=False)
    # Stift: reviewed long ago, overdue. Bericht: due in the future.
    old = srs.review(None, 5, NOW - timedelta(days=60), sched)
    future = srs.review(None, 5, NOW, sched)
    cards = [replace(c, schedule=old) if c.term == "Stift" else
             replace(c, schedule=future) if c.term == "Bericht" else c for c in cards]
    deck = build_deck(cards, mode="due", now=NOW, seed=1)
    terms = [c.term for c in deck]
    assert terms[0] == "Stift"
    assert "Bericht" not in terms
    assert set(terms[1:]) == {"werden", "riesig"}  # never scheduled
    assert by_term  # fixture sanity


def test_due_mode_needs_now(cards):
    with pytest.raises(ValueError, match="now"):
        build_deck(cards, mode="due")


def _three_due_one_new(cards):
    """Stift, Bericht and werden overdue (reviewed 60/30/10 days ago); riesig never scheduled."""
    sched = srs.make_scheduler(fuzz=False)
    ago = {"Stift": 60, "Bericht": 30, "werden": 10}
    return [replace(c, schedule=srs.review(None, 5, NOW - timedelta(days=ago[c.term]), sched))
            if c.term in ago else c for c in cards]


def test_due_mode_reserves_slots_for_new_words(cards):
    # Without the reservation a session full of due words never reaches a new word.
    cards = _three_due_one_new(cards)
    deck = build_deck(cards, mode="due", now=NOW, seed=1, limit=2, new_per_session=1)
    assert [c.term for c in deck] == ["Stift", "riesig"]  # most forgotten due word, then the new one


def test_due_mode_without_reservation_fills_with_due_first(cards):
    cards = _three_due_one_new(cards)
    deck = build_deck(cards, mode="due", now=NOW, seed=1, limit=2, new_per_session=0)
    assert [c.term for c in deck] == ["Stift", "Bericht"]


def test_unused_reservation_never_shortens_the_session(cards):
    cards = _three_due_one_new(cards)
    # Quota 3 but only one new word: the other two slots go to due words.
    deck = build_deck(cards, mode="due", now=NOW, seed=1, limit=3, new_per_session=3)
    assert [c.term for c in deck] == ["Stift", "Bericht", "riesig"]
    # Quota above the limit: capped at the limit.
    deck = build_deck(cards, mode="due", now=NOW, seed=1, limit=1, new_per_session=5)
    assert [c.term for c in deck] == ["riesig"]


def test_new_per_session_must_not_be_negative(cards):
    with pytest.raises(ValueError, match="new_per_session"):
        build_deck(cards, mode="due", now=NOW, new_per_session=-1)
