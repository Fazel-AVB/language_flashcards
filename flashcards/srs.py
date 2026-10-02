"""Spaced repetition: when each word is due again, using FSRS.

FSRS (Free Spaced Repetition Scheduler, the algorithm Anki ships) keeps per word
a *stability* (how many days until recall drops to 90%) and a *difficulty*, and
from those a due date. The maths is the `fsrs` package; this module only maps it
onto the workbook and onto the app's 1-5 grades.

Where the schedule lives: columns M..R of the word's own row (SRS_COLUMNS). Kept
in the workbook, not in a separate database, so it moves with the row when you
sort or insert rows in Excel and is covered by the write-back backups. The
schema reserves M onward for bookkeeping (README, "What the workbook needs"), and
the dashboards only read A..L.

Two scores, on purpose:
  Confidence (column L) -- your averaged 1-5 self-rating; feeds the dashboards
                           and the weighted draw. Unchanged by this module.
  Schedule  (M..R)      -- FSRS state; decides *when* a word comes back.
Both are updated by every grade.
"""

from __future__ import annotations

import math
from dataclasses import dataclass
from datetime import date, datetime, timedelta, timezone, tzinfo

import fsrs

# Column -> header. Order matters: schedule_from_cells reads cells in this order.
SRS_COLUMNS = ("M", "N", "O", "P", "Q", "R")
SRS_HEADERS = {
    "M": "Due (FSRS)",
    "N": "State (FSRS)",
    "O": "Step (FSRS)",
    "P": "Stability (FSRS)",
    "Q": "Difficulty (FSRS)",
    "R": "Last review (FSRS)",
}
SRS_FIRST_COL_INDEX = 13  # M, 1-based
SRS_LAST_COL_INDEX = 18   # R

# The app keeps its five buttons (decided with the user): 3 "getting there" and
# 4 "solid" are both a successful recall in FSRS terms, so both are Good. Only 1
# means the word was not recalled (Again); Hard is a recall with real effort.
GRADE_TO_RATING = {
    1: fsrs.Rating.Again,
    2: fsrs.Rating.Hard,
    3: fsrs.Rating.Good,
    4: fsrs.Rating.Good,
    5: fsrs.Rating.Easy,
}

_STATE_NAMES = {s: s.name for s in fsrs.State}          # Learning / Review / Relearning
_STATE_BY_NAME = {s.name.lower(): s for s in fsrs.State}

# Stored to 4 decimals: FSRS outputs ~16 digits, which is noise in a workbook
# cell; 1e-4 days of stability is under ten seconds.
_DECIMALS = 4


@dataclass(frozen=True)
class Schedule:
    """One word's FSRS state, as stored in columns M..R. Datetimes are UTC."""

    due: datetime
    state: str                 # "Learning" | "Review" | "Relearning"
    step: int | None           # position in the (re)learning steps; None in Review
    stability: float | None
    difficulty: float | None
    last_review: datetime | None


# --------------------------------------------------------------------------- #
# Scheduling
# --------------------------------------------------------------------------- #

def make_scheduler(*, fuzz: bool = True) -> fsrs.Scheduler:
    """The scheduler with FSRS's published defaults (desired retention 0.9,
    learning steps 1 min and 10 min).

    Fuzzing spreads intervals by a few percent so words added on the same day do
    not all fall due on the same later day; tests turn it off for exact dates.
    """
    return fsrs.Scheduler(enable_fuzzing=fuzz)


def review(schedule: Schedule | None, grade: int, now: datetime,
           scheduler: fsrs.Scheduler | None = None) -> Schedule:
    """The schedule after grading the word `grade` (1-5) at `now`.

    `schedule` None means the word has never been scheduled (a new word).
    """
    if grade not in GRADE_TO_RATING:
        raise ValueError(f"grade {grade} out of range 1..5")
    scheduler = scheduler or make_scheduler()
    card = _to_fsrs(schedule)
    card, _log = scheduler.review_card(card, GRADE_TO_RATING[grade], _utc(now))
    return _from_fsrs(card)


def retrievability(schedule: Schedule | None, now: datetime,
                   scheduler: fsrs.Scheduler | None = None) -> float | None:
    """Estimated chance (0..1) you would recall the word now; None for a new word."""
    if schedule is None or schedule.last_review is None:
        return None
    scheduler = scheduler or make_scheduler()
    return scheduler.get_card_retrievability(_to_fsrs(schedule), _utc(now))


# --------------------------------------------------------------------------- #
# Due status
# --------------------------------------------------------------------------- #

NEW, DUE, LATER = "new", "due", "later"


def status(schedule: Schedule | None, now: datetime, local_tz: tzinfo | None = None) -> str:
    """"new" (never scheduled), "due" or "later".

    Review-state words are due for the whole *local calendar day* of their due
    time, as in Anki: FSRS gives "in 2 days" as review time + 48 h, and a word
    studied at 21:00 should not be hidden until 21:00 two days on. Learning and
    relearning steps are minutes long, so those use the exact time.
    """
    if schedule is None:
        return NEW
    now = _utc(now)
    if schedule.state != "Review":
        return DUE if schedule.due <= now else LATER
    return DUE if _local_date(schedule.due, local_tz) <= _local_date(now, local_tz) else LATER


def describe_due(due: datetime, now: datetime, local_tz: tzinfo | None = None) -> str:
    """Short human text for a due time: "now", "in 10 min", "tomorrow", "in 4 days"."""
    delta = _utc(due) - _utc(now)
    if delta <= timedelta(0):
        return "now"
    if delta < timedelta(hours=1):
        return f"in {max(1, math.ceil(delta.total_seconds() / 60))} min"
    days = (_local_date(due, local_tz) - _local_date(now, local_tz)).days
    if days <= 0:
        return f"in {math.ceil(delta.total_seconds() / 3600)} h"
    if days == 1:
        return "tomorrow"
    if days < 60:
        return f"in {days} days"
    if days < 730:
        return f"in {round(days / 30.4)} months"
    return f"in {days / 365.25:.1f} years"


# --------------------------------------------------------------------------- #
# Workbook cells <-> Schedule
# --------------------------------------------------------------------------- #

def schedule_from_cells(cells: list) -> Schedule | None:
    """Parse the six cells M..R. All blank -> None (a new word).

    Raises ValueError on a partly filled or unreadable schedule, so a hand edit
    that broke it is reported (workbook.load_cards collects these) instead of
    silently restarting the word from scratch.
    """
    cells = list(cells) + [None] * (len(SRS_COLUMNS) - len(cells))
    due, state, step, stability, difficulty, last = (_blank_to_none(c) for c in cells[:6])
    if all(v is None for v in (due, state, step, stability, difficulty, last)):
        return None
    if due is None or state is None:
        raise ValueError("due date and state are both required")
    state_enum = _STATE_BY_NAME.get(str(state).strip().lower())
    if state_enum is None:
        raise ValueError(f"unknown state {state!r} (expected Learning, Review or Relearning)")
    return Schedule(
        due=_parse_time(due),
        state=state_enum.name,
        step=None if step is None else int(float(step)),
        stability=None if stability is None else float(stability),
        difficulty=None if difficulty is None else float(difficulty),
        last_review=None if last is None else _parse_time(last),
    )


def schedule_to_cells(schedule: Schedule) -> dict[str, str | float | None]:
    """Column -> cell value for writeback.apply_updates. Text for times and the
    state (readable in Excel), numbers for the rest, None to clear a cell."""
    return {
        "M": format_time(schedule.due),
        "N": schedule.state,
        "O": schedule.step,
        "P": None if schedule.stability is None else round(schedule.stability, _DECIMALS),
        "Q": None if schedule.difficulty is None else round(schedule.difficulty, _DECIMALS),
        "R": None if schedule.last_review is None else format_time(schedule.last_review),
    }


def format_time(dt: datetime) -> str:
    """UTC as "2026-10-05T09:20:00Z".

    Text, not an Excel date: an Excel date needs a number format in styles.xml,
    which write-back deliberately never edits, and the "Z" keeps Excel from
    auto-converting the cell to a local date when you edit near it. UTC so the
    schedule stays correct if the laptop changes time zone.
    """
    return _utc(dt).strftime("%Y-%m-%dT%H:%M:%SZ")


# --------------------------------------------------------------------------- #
# helpers
# --------------------------------------------------------------------------- #

def _to_fsrs(schedule: Schedule | None) -> fsrs.Card:
    if schedule is None:
        return fsrs.Card()
    return fsrs.Card(
        state=_STATE_BY_NAME[schedule.state.lower()],
        step=schedule.step,
        stability=schedule.stability,
        difficulty=schedule.difficulty,
        due=schedule.due,
        last_review=schedule.last_review,
    )


def _from_fsrs(card: fsrs.Card) -> Schedule:
    return Schedule(
        due=_utc(card.due),
        state=_STATE_NAMES[card.state],
        step=card.step,
        stability=card.stability,
        difficulty=card.difficulty,
        last_review=None if card.last_review is None else _utc(card.last_review),
    )


def _parse_time(value) -> datetime:
    if isinstance(value, datetime):
        return _utc(value)
    if isinstance(value, date):
        return datetime(value.year, value.month, value.day, tzinfo=timezone.utc)
    text = str(value).strip()
    # fromisoformat accepts a trailing "Z" only from Python 3.11; this package supports 3.10.
    if text.endswith(("Z", "z")):
        text = text[:-1] + "+00:00"
    try:
        return _utc(datetime.fromisoformat(text))
    except ValueError as exc:
        raise ValueError(f"unreadable date {value!r}") from exc


def _utc(dt: datetime) -> datetime:
    # A naive datetime is taken as UTC: that is what format_time writes, and an
    # openpyxl cell typed as a date by hand comes back naive.
    if dt.tzinfo is None:
        return dt.replace(tzinfo=timezone.utc)
    return dt.astimezone(timezone.utc)


def _local_date(dt: datetime, local_tz: tzinfo | None) -> date:
    # local_tz None -> the computer's own zone (astimezone() with no argument).
    return _utc(dt).astimezone(local_tz).date()


def _blank_to_none(value):
    if isinstance(value, str) and not value.strip():
        return None
    return value
