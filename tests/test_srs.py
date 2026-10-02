"""FSRS mapping onto the workbook (srs.py): grades, cells, due status."""

from datetime import datetime, timedelta, timezone

import fsrs
import pytest

from flashcards import srs

NOW = datetime(2026, 10, 2, 9, 0, tzinfo=timezone.utc)
UTC = timezone.utc
CET = timezone(timedelta(hours=2))


def sched():
    return srs.make_scheduler(fuzz=False)


def test_grade_mapping_keeps_five_buttons():
    assert [srs.GRADE_TO_RATING[g] for g in range(1, 6)] == [
        fsrs.Rating.Again, fsrs.Rating.Hard, fsrs.Rating.Good, fsrs.Rating.Good, fsrs.Rating.Easy]


def test_new_word_good_enters_learning_easy_skips_it():
    good = srs.review(None, 3, NOW, sched())
    assert good.state == "Learning" and good.due == NOW + timedelta(minutes=10)
    easy = srs.review(None, 5, NOW, sched())
    assert easy.state == "Review" and easy.due - NOW >= timedelta(days=1)


def test_again_is_sooner_than_easy():
    s = srs.review(srs.review(None, 5, NOW, sched()), 5, NOW + timedelta(days=8), sched())
    later = NOW + timedelta(days=40)
    assert srs.review(s, 1, later, sched()).due < srs.review(s, 5, later, sched()).due


def test_bad_grade():
    with pytest.raises(ValueError):
        srs.review(None, 0, NOW)


def test_cells_round_trip():
    s = srs.review(srs.review(None, 3, NOW, sched()), 3, NOW + timedelta(minutes=10), sched())
    cells = srs.schedule_to_cells(s)
    assert cells["M"].endswith("Z") and cells["N"] == s.state
    back = srs.schedule_from_cells([cells[c] for c in srs.SRS_COLUMNS])
    assert back.due == s.due.replace(microsecond=0)
    assert back.state == s.state and back.step == s.step
    assert back.stability == pytest.approx(s.stability, abs=1e-4)


def test_cells_blank_is_new_word():
    assert srs.schedule_from_cells([None] * 6) is None
    assert srs.schedule_from_cells(["", " ", None]) is None


def test_cells_partial_or_bad_raise():
    with pytest.raises(ValueError, match="required"):
        srs.schedule_from_cells([None, "Review", None, 3.0, 5.0, None])
    with pytest.raises(ValueError, match="unknown state"):
        srs.schedule_from_cells(["2026-10-05T09:00:00Z", "Sleeping"])
    with pytest.raises(ValueError, match="unreadable date"):
        srs.schedule_from_cells(["next tuesday", "Review"])


def test_cells_accept_hand_typed_excel_date():
    # A date typed in Excel comes back from openpyxl as a naive datetime.
    s = srs.schedule_from_cells([datetime(2026, 10, 5), "review", 0, 3, 5, None])
    assert s.due == datetime(2026, 10, 5, tzinfo=UTC) and s.state == "Review" and s.step == 0


def test_status_review_is_due_all_local_day():
    # Due 21:00 local; at 08:00 local the same day it already counts as due.
    s = srs.Schedule(due=datetime(2026, 10, 5, 19, 0, tzinfo=UTC), state="Review", step=None,
                     stability=3, difficulty=5, last_review=NOW)
    assert srs.status(s, datetime(2026, 10, 5, 6, 0, tzinfo=UTC), CET) == srs.DUE
    assert srs.status(s, datetime(2026, 10, 4, 21, 0, tzinfo=UTC), CET) == srs.LATER
    assert srs.status(None, NOW) == srs.NEW


def test_status_learning_uses_exact_time():
    s = srs.Schedule(due=NOW + timedelta(minutes=10), state="Learning", step=1,
                     stability=2, difficulty=5, last_review=NOW)
    assert srs.status(s, NOW, UTC) == srs.LATER
    assert srs.status(s, NOW + timedelta(minutes=10), UTC) == srs.DUE


def test_describe_due():
    assert srs.describe_due(NOW, NOW) == "now"
    assert srs.describe_due(NOW + timedelta(minutes=10), NOW, UTC) == "in 10 min"
    assert srs.describe_due(NOW + timedelta(days=1), NOW, UTC) == "tomorrow"
    assert srs.describe_due(NOW + timedelta(days=12), NOW, UTC) == "in 12 days"
    assert srs.describe_due(NOW + timedelta(days=120), NOW, UTC) == "in 4 months"


def test_retrievability():
    assert srs.retrievability(None, NOW) is None
    s = srs.review(None, 5, NOW, sched())
    assert srs.retrievability(s, NOW + timedelta(days=1), sched()) > srs.retrievability(s, NOW + timedelta(days=30), sched())
