"""Canonical card model, the positional column contract, and the language pair.

The reference workbook stores one word per row across several "topic" sheets. All
topic sheets, in every workbook, share one fixed layout (columns A..L, header on
row 1). Column *positions* are the contract; the header *labels* for columns D and
E differ by sheet (column D is "Article", "Article / Auxiliary" or "Article (n/a)";
column E is "Plural", "Plural / Verb forms" or "Comparative / Superlative"). We
therefore map by position, not by label, and keep the sheet's real labels only for
display captions.

THE ONE THING READ FROM A LABEL: cells A1 and B1 hold the bare *language names*
("German"/"English", or "English"/"Persian"). That is how one app serves several
language pairs without a config entry per workbook -- see `language_pair_from_header`
and the schema table in README.md. Do not "improve" those headers into prose like
"English Term / Expression": the text is displayed verbatim as the language name.

Columns M..R hold the word's FSRS schedule (see srs.py). They are parsed by
`workbook.load_cards`, not here, so a broken schedule can be reported per row.

Downstream: `Card.sheet` + `Card.row` are the write-back address used by
`writeback.apply_updates`. Do not derive the row from list order --
filtered/blank rows would shift it.
"""

from __future__ import annotations

import math
import re
from dataclasses import dataclass
from datetime import date, datetime

from .srs import Schedule

# Column order on every topic sheet, A..L. Position is the contract; see module docstring.
# `term` is the word being learned (column A), `meaning` its native-language gloss (B).
FIELD_ORDER = (
    "term",              # A -- in the language named by A1
    "meaning",           # B -- in the language named by B1
    "word_type",         # C
    "article_aux",       # D  (blank for languages without articles, e.g. English)
    "forms",             # E
    "pronunciation",     # F
    "example_term",      # G
    "example_meaning",   # H
    "synonyms",          # I
    "notes",             # J
    "date_added",        # K
    "confidence",        # L
)

# 1-based Excel column of the confidence value; hard-coded because it anchors write-back.
CONFIDENCE_COL = "L"
CONFIDENCE_COL_INDEX = 12

# Sheets to skip by name. LOAD-BEARING, do not delete in favour of the structural
# `is_topic_header` test alone: German_learning.xlsx's "Card Pool" sheet has a
# non-empty A1 ("Topic") and L1 == "Confidence", so it PASSES the structural test
# while being a LET/LAMBDA dynamic-array dashboard. Matched as a pattern rather
# than a fixed set because dashboards get renamed ("Overview" became
# "Overview & Dashboard" in one workbook and back again).
NON_TOPIC_NAME_RE = re.compile(
    r"\b(overview|dashboard|flashcards?|card\s*pool|summary|stats)\b", re.IGNORECASE
)

# Scripts written right-to-left, by the language name as it appears in A1/B1.
# Used only for display: the card text gets dir="rtl" so Persian does not render
# jumbled next to Latin script.
RTL_LANGUAGES = frozenset({
    "persian", "farsi", "arabic", "hebrew", "urdu", "pashto", "dari", "kurdish",
})

CONFIDENCE_MIN = 1
CONFIDENCE_MAX = 5

# A grade is averaged into the stored score (see `blend_confidence`), so the
# value is no longer an integer. Results are snapped to this grid rather than
# kept as the raw quotient: repeated averaging otherwise walks off into quarters
# and eighths (1.75, 2.8125, ...) -- meaningless precision on a self-assessed
# 1-5 scale, and unreadable in the workbook.
CONFIDENCE_STEP = 0.5


@dataclass(frozen=True)
class LanguagePair:
    """Which language is being learned, and which one glosses it.

    Read from the A1/B1 headers of a topic sheet, so a workbook declares its own
    pair and the app hard-codes neither. `term` is column A, `native` column B.
    """

    term: str
    native: str

    def front_label(self, direction: str) -> str:
        """Caption for the current card direction, e.g. "English -> Persian"."""
        if direction == "native_to_term":
            return f"{self.native} → {self.term}"
        return f"{self.term} → {self.native}"


@dataclass(frozen=True)
class Card:
    """One vocabulary entry, plus its physical location for write-back."""

    topic: str
    term: str                  # the word being learned (column A)
    meaning: str               # its native-language gloss (column B)
    word_type: str | None
    article_aux: str | None
    forms: str | None
    pronunciation: str | None
    example_term: str | None
    example_meaning: str | None
    synonyms: str | None
    notes: str | None
    date_added: date | None
    confidence: float | None   # half-steps; grades are averaged in, not substituted

    languages: LanguagePair  # this sheet's pair, for UI labels and RTL handling

    sheet: str          # source sheet name (== topic); explicit so write-back never guesses
    row: int            # 1-based Excel row of this word

    article_label: str  # this sheet's real header for column D, for UI captions
    forms_label: str    # this sheet's real header for column E

    schedule: Schedule | None = None  # FSRS state from columns M..R; None = never scheduled

    @property
    def id(self) -> str:
        """Stable per-session key. `sheet!row` is unique and survives re-filtering."""
        return f"{self.sheet}!{self.row}"


def is_topic_header(header_row: list) -> bool:
    """True if a sheet's first row looks like a word table (not a dashboard).

    Structural, not name-based: columns A and B must be filled (the two language
    names) and column L must mention confidence. This is what lets a new topic
    sheet, or a whole new language pair, be picked up with no code change.

    NOT sufficient on its own -- see `NON_TOPIC_NAME_RE`.
    """
    if not header_row or len(header_row) < CONFIDENCE_COL_INDEX:
        return False
    term = _clean(header_row[0])
    native = _clean(header_row[1])
    confidence = _clean(header_row[CONFIDENCE_COL_INDEX - 1])
    return bool(term) and bool(native) and "confidence" in (confidence or "").lower()


def is_topic_sheet(name: str, header_row: list) -> bool:
    """Both gates: the name is not a dashboard's and the header row is a word table."""
    return not NON_TOPIC_NAME_RE.search(name or "") and is_topic_header(header_row)


def language_pair_from_header(header_row: list) -> LanguagePair:
    """Read the pair from A1/B1, verbatim.

    Deliberately no parsing of "English Term / Expression" into "English": every
    heuristic that strips decoration also mangles a legitimate name (e.g. a B1 of
    "Meaning (Persian)"), and it would do so silently. The schema requires bare
    names, so a decorated header shows up immediately as a wrong label in the UI
    rather than as a subtly wrong split.
    """
    term = _clean(header_row[0]) if header_row else None
    native = _clean(header_row[1]) if len(header_row) > 1 else None
    return LanguagePair(term=term or "Term", native=native or "Meaning")


def is_rtl(language: str | None) -> bool:
    """True if `language` is written right-to-left (display concern only)."""
    return (language or "").strip().lower() in RTL_LANGUAGES


# Language name as written in A1/B1 -> BCP 47 primary tag, for picking a
# speech voice in the browser (app.js speak()). Only the primary tag: the
# page matches any regional voice (de-DE, de-AT, ...) the computer has.
LANGUAGE_CODES = {
    "arabic": "ar", "chinese": "zh", "danish": "da", "dari": "fa", "dutch": "nl",
    "english": "en", "farsi": "fa", "finnish": "fi", "french": "fr", "german": "de",
    "greek": "el", "hebrew": "he", "hindi": "hi", "italian": "it", "japanese": "ja",
    "korean": "ko", "norwegian": "nb", "persian": "fa", "polish": "pl",
    "portuguese": "pt", "russian": "ru", "spanish": "es", "swedish": "sv",
    "turkish": "tr", "ukrainian": "uk", "urdu": "ur",
}


def language_code(language: str | None) -> str | None:
    """BCP 47 code for a language name ("German" -> "de"); None if unknown,
    in which case the page offers no pronunciation for that side."""
    return LANGUAGE_CODES.get((language or "").strip().lower())


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


def parse_row(
    topic: str,
    headers: list,
    cells: list,
    excel_row: int,
    languages: LanguagePair,
) -> Card | None:
    """Build a Card from one data row, or None if it has no term in column A.

    `headers` and `cells` are the full A..L slices for the sheet. Missing
    trailing cells are tolerated (openpyxl trims empty tails).
    """
    padded = list(cells) + [None] * (len(FIELD_ORDER) - len(cells))
    values = {field: _clean(padded[i]) for i, field in enumerate(FIELD_ORDER)}

    if not values["term"]:
        return None

    hdr = list(headers) + [None] * (len(FIELD_ORDER) - len(headers))

    return Card(
        topic=topic,
        term=values["term"],
        meaning=values["meaning"] or "",
        word_type=values["word_type"],
        article_aux=values["article_aux"],
        forms=values["forms"],
        pronunciation=values["pronunciation"],
        example_term=values["example_term"],
        example_meaning=values["example_meaning"],
        synonyms=values["synonyms"],
        notes=values["notes"],
        date_added=_as_date(padded[10]),
        confidence=coerce_confidence(padded[11]),
        languages=languages,
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
