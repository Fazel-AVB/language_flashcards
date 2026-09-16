"""Canonical card model and the header -> field mapping.

The reference workbook stores one word per row across several "topic" sheets.
Column *positions* (A..L) are identical on every topic sheet, but the header
*labels* differ by sheet (e.g. column D is "Article", "Article / Auxiliary" or
"Article (n/a)"; column E is "Plural", "Plural / Verb forms" or
"Comparative / Superlative"). We therefore map by position, not by label, and
keep the sheet's real labels only for display captions.

Downstream: `Card.sheet` + `Card.row` are the write-back address used by
`writeback.apply_confidence_updates`. Do not derive the row from list order --
filtered/blank rows would shift it.
"""

from __future__ import annotations

import math
from dataclasses import dataclass
from datetime import date, datetime

# Column order on every topic sheet, A..L. Position is the contract; see module docstring.
FIELD_ORDER = (
    "german",         # A
    "english",        # B
    "word_type",      # C
    "article_aux",    # D
    "forms",          # E
    "pronunciation",  # F
    "example_de",     # G
    "example_en",     # H
    "synonyms",       # I
    "notes",          # J
    "date_added",     # K
    "confidence",     # L
)

# 1-based Excel column of the confidence value; hard-coded because it anchors write-back.
CONFIDENCE_COL = "L"
CONFIDENCE_COL_INDEX = 12

# Sheets that are formula-driven dashboards, not word tables. Never read or written as cards.
NON_TOPIC_SHEETS = frozenset({"Overview", "Flashcards", "Card Pool"})

CONFIDENCE_MIN = 1
CONFIDENCE_MAX = 5

# A grade is averaged into the stored score (see `blend_confidence`), so the
# value is no longer an integer. Results are snapped to this grid rather than
# kept as the raw quotient: repeated averaging otherwise walks off into quarters
# and eighths (1.75, 2.8125, ...) -- meaningless precision on a self-assessed
# 1-5 scale, and unreadable in the workbook.
CONFIDENCE_STEP = 0.5


@dataclass(frozen=True)
class Card:
    """One vocabulary entry, plus its physical location for write-back."""

    topic: str
    german: str
    english: str
    word_type: str | None
    article_aux: str | None
    forms: str | None
    pronunciation: str | None
    example_de: str | None
    example_en: str | None
    synonyms: str | None
    notes: str | None
    date_added: date | None
    confidence: float | None   # half-steps; grades are averaged in, not substituted

    sheet: str          # source sheet name (== topic); explicit so write-back never guesses
    row: int            # 1-based Excel row of this word

    article_label: str  # this sheet's real header for column D, for UI captions
    forms_label: str    # this sheet's real header for column E

    @property
    def id(self) -> str:
        """Stable per-session key. `sheet!row` is unique and survives re-filtering."""
        return f"{self.sheet}!{self.row}"


def is_topic_header(header_row: list) -> bool:
    """True if a sheet's first row looks like a word table (not a dashboard).

    Checks column A == "German" and column L mentions confidence, so an added
    topic sheet is picked up automatically while dashboards are skipped.
    """
    if not header_row or len(header_row) < CONFIDENCE_COL_INDEX:
        return False
    first = _clean(header_row[0])
    last = _clean(header_row[CONFIDENCE_COL_INDEX - 1])
    return bool(first) and first.lower() == "german" and "confidence" in (last or "").lower()


def _snap(value: float) -> float:
    """Clamp to [1, 5] and round to the nearest CONFIDENCE_STEP.

    Ties go up via floor(x + 0.5), NOT round(): round() is banker's rounding, so
    it sends 3.25 down to 3.0 but 3.75 up to 4.0. That asymmetry surfaces as a
    word that mysteriously refuses to improve, so do not "simplify" this back.
    """
    stepped = math.floor(value / CONFIDENCE_STEP + 0.5) * CONFIDENCE_STEP
    return float(max(CONFIDENCE_MIN, min(CONFIDENCE_MAX, stepped)))


def coerce_confidence(value) -> float | None:
    """Normalise a raw cell to a float in [1, 5] on the half-step grid, or None.

    Out-of-range values are clamped rather than dropped: a stray 0 or 6 should
    still round-trip to a usable grade instead of silently becoming None. A
    hand-edited 2.8 is snapped onto the grid for the same reason.
    """
    if value is None or (isinstance(value, str) and not value.strip()):
        return None
    try:
        n = float(value)
    except (TypeError, ValueError):
        return None
    return _snap(n)


def blend_confidence(old: float | None, grade: int) -> float:
    """Combine the stored confidence with a fresh grade: their mean.

    A word's score is the average of what it was and how you just rated it, so
    one bad day cannot erase a well-known word and one lucky guess cannot mark a
    weak one as mastered. An ungraded word has nothing to average against, so its
    first grade is taken as-is.
    """
    if not (CONFIDENCE_MIN <= grade <= CONFIDENCE_MAX):
        raise ValueError(f"grade {grade} out of range {CONFIDENCE_MIN}..{CONFIDENCE_MAX}")
    if old is None:
        return float(grade)
    return _snap((old + grade) / 2)


def confidence_to_cell(value: float) -> str:
    """Canonical <v> text for the .xlsx: '3' for 3.0, '3.5' for 3.5.

    Whole numbers must not be written as '3.0': every pre-averaging value in the
    workbook is a bare integer, and a needless '.0' makes untouched and rewritten
    cells look different for no reason.
    """
    return f"{value:g}"


def format_confidence(value: float | None) -> str:
    """Human-readable score for the UI: '3', '3.5', or 'ungraded'."""
    return "ungraded" if value is None else confidence_to_cell(value)


def parse_row(topic: str, headers: list, cells: list, excel_row: int) -> Card | None:
    """Build a Card from one data row, or None if it has no German word.

    `headers` and `cells` are the full A..L slices for the sheet. Missing
    trailing cells are tolerated (openpyxl trims empty tails).
    """
    padded = list(cells) + [None] * (len(FIELD_ORDER) - len(cells))
    values = {field: _clean(padded[i]) for i, field in enumerate(FIELD_ORDER)}

    if not values["german"]:
        return None

    hdr = list(headers) + [None] * (len(FIELD_ORDER) - len(headers))

    return Card(
        topic=topic,
        german=values["german"],
        english=values["english"] or "",
        word_type=values["word_type"],
        article_aux=values["article_aux"],
        forms=values["forms"],
        pronunciation=values["pronunciation"],
        example_de=values["example_de"],
        example_en=values["example_en"],
        synonyms=values["synonyms"],
        notes=values["notes"],
        date_added=_as_date(padded[10]),
        confidence=coerce_confidence(padded[11]),
        sheet=topic,
        row=excel_row,
        article_label=_clean(hdr[3]) or "Article / Auxiliary",
        forms_label=_clean(hdr[4]) or "Forms",
    )


def _clean(value) -> str | None:
    """Trim strings; pass through None; stringify everything else."""
    if value is None:
        return None
    if isinstance(value, str):
        value = value.strip()
        return value or None
    return str(value)


def _as_date(value) -> date | None:
    if isinstance(value, datetime):
        return value.date()
    if isinstance(value, date):
        return value
    return None
