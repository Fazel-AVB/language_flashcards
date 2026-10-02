"""Language-pair handling: the workbook declares its own pair, the app reads it.

These are the regression guards for multi-pair support. The app previously
hard-coded "German" and "English"; if any of these fail, a label or a direction
has been pinned to one language again.
"""

from __future__ import annotations

import json

import pytest

from flashcards import config
from flashcards.models import (
    LanguagePair,
    is_rtl,
    is_topic_header,
    is_topic_sheet,
    language_pair_from_header,
)
from flashcards.workbook import load_cards
from flashcards.writeback import apply_confidence_updates


# --------------------------------------------------------------------------- #
# the pair comes from A1/B1
# --------------------------------------------------------------------------- #

def test_pair_read_from_headers_de_en(workbook_path):
    data = load_cards(str(workbook_path))
    assert data.languages == LanguagePair(term="German", native="English")
    assert not data.language_conflicts


def test_pair_read_from_headers_en_fa(en_fa_workbook_path):
    data = load_cards(str(en_fa_workbook_path))
    assert data.languages == LanguagePair(term="English", native="Persian")
    assert not data.language_conflicts


def test_every_card_carries_its_sheet_pair(en_fa_workbook_path):
    # Cards hold the pair so a mixed workbook still renders each card correctly.
    cards = load_cards(str(en_fa_workbook_path)).cards
    assert {c.languages for c in cards} == {LanguagePair("English", "Persian")}


def test_pair_is_not_parsed_out_of_decorated_headers():
    # Verbatim by design: any decoration-stripping heuristic also mangles a
    # legitimate name, and silently. See models.language_pair_from_header.
    pair = language_pair_from_header(["English Term / Expression", "Persian Meaning"])
    assert pair.term == "English Term / Expression"


def test_front_label_follows_the_direction():
    pair = LanguagePair(term="English", native="Persian")
    assert pair.front_label("term_to_native") == "English → Persian"
    assert pair.front_label("native_to_term") == "Persian → English"


# --------------------------------------------------------------------------- #
# topic-sheet detection
# --------------------------------------------------------------------------- #

def test_card_pool_is_excluded_only_by_name(workbook_path):
    """The case that makes NON_TOPIC_NAME_RE load-bearing.

    "Card Pool" has a filled A1/B1 and an L1 of "Confidence", so it satisfies the
    structural test while being a dynamic-array dashboard. If someone deletes the
    name check because "the structural test covers it", this fails.
    """
    pool_header = ["Topic", "German", "English", "Word type", "Article / Aux",
                   "Plural / Forms", "Pronunciation", "Example (DE)", "Example (EN)",
                   "Synonyms", "Notes", "Confidence"]
    assert is_topic_header(pool_header) is True          # structure alone accepts it
    assert is_topic_sheet("Card Pool", pool_header) is False   # name rejects it

    assert "Card Pool" not in load_cards(str(workbook_path)).topics


def test_renamed_dashboard_is_excluded(en_fa_workbook_path):
    # "Overview" became "Overview & Dashboard" in one workbook; matching a pattern
    # rather than a fixed set is what survives that.
    assert "Overview & Dashboard" not in load_cards(str(en_fa_workbook_path)).topics


def test_header_needs_both_language_cells():
    base = ["English", "Persian"] + [None] * 9 + ["Confidence (1-5)"]
    assert is_topic_header(base)
    assert not is_topic_header([None, "Persian"] + [None] * 9 + ["Confidence (1-5)"])
    assert not is_topic_header(["English", None] + [None] * 9 + ["Confidence (1-5)"])
    assert not is_topic_header(["English", "Persian"] + [None] * 9 + ["Status"])


# --------------------------------------------------------------------------- #
# parsing a non-German pair
# --------------------------------------------------------------------------- #

def test_en_fa_rows_parse(en_fa_workbook_path):
    cards = {c.term: c for c in load_cards(str(en_fa_workbook_path)).cards}
    assert set(cards) == {"charm", "composure", "anew"}

    charm = cards["charm"]
    assert charm.meaning == "جذابیت"
    assert charm.article_aux is None       # English has no articles; column D is blank
    assert charm.forms == "charms"
    assert charm.pronunciation == "/tʃɑːm/"
    assert charm.confidence == 1
    assert (charm.sheet, charm.row) == ("Emotions & Mind", 2)

    assert cards["anew"].confidence is None   # blank cell -> ungraded


def test_rtl_detection():
    assert is_rtl("Persian") and is_rtl("farsi") and is_rtl("  Arabic ")
    assert not is_rtl("English") and not is_rtl("German") and not is_rtl(None)


# --------------------------------------------------------------------------- #
# write-back is pair-agnostic
# --------------------------------------------------------------------------- #

def test_writeback_into_en_fa_workbook(en_fa_workbook_path, tmp_path):
    """Confidence is at column L in every pair, so write-back needs no per-pair code."""
    data = load_cards(str(en_fa_workbook_path))
    result = apply_confidence_updates(
        str(en_fa_workbook_path),
        {("Emotions & Mind", 2): 3.5},
        original_mtime=data.mtime,
        backup_dir=str(tmp_path / "backups"),
    )
    assert result.cells_written == 1

    reloaded = {c.term: c for c in load_cards(str(en_fa_workbook_path)).cards}
    assert reloaded["charm"].confidence == 3.5
    assert reloaded["composure"].confidence == 4      # untouched
    # Persian text must survive the zip/XML rewrite unharmed.
    assert reloaded["charm"].meaning == "جذابیت"


# --------------------------------------------------------------------------- #
# config migration
# --------------------------------------------------------------------------- #

@pytest.mark.parametrize(
    "stored,expected",
    [("de_en", "term_to_native"), ("en_de", "native_to_term"),
     ("mixed", "mixed"), ("term_to_native", "term_to_native")],
)
def test_legacy_direction_is_migrated(fc_home, stored, expected):
    """A config.json from the German-only version must not break the first launch."""
    fc_home.mkdir(parents=True, exist_ok=True)
    (fc_home / "config.json").write_text(json.dumps({"reference_path": None, "prefs": {"direction": stored}}))
    assert config.load_config()["prefs"]["direction"] == expected


def test_language_codes_for_speech():
    from flashcards.models import language_code

    assert language_code("German") == "de"
    assert language_code(" persian ") == "fa"
    assert language_code("Klingon") is None
    assert language_code(None) is None
