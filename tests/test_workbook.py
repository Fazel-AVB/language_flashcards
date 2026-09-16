from datetime import date

import pytest

from flashcards.models import coerce_confidence
from flashcards.workbook import WorkbookError, load_cards


def test_loads_only_topic_sheets(workbook_path):
    data = load_cards(str(workbook_path))
    assert data.topics == ["School & Office", "Verbs", "Adjectives & Adverbs"]
    assert {c.german for c in data.cards} == {"Stift", "Bericht", "werden", "riesig"}


def test_position_mapping_survives_header_variants(workbook_path):
    cards = {c.german: c for c in load_cards(str(workbook_path)).cards}

    verb = cards["werden"]
    assert verb.article_aux == "sein"
    assert verb.forms == "wird – wurde – ist geworden"
    assert verb.article_label == "Article / Auxiliary"
    assert verb.forms_label == "Plural / Verb forms"

    adj = cards["riesig"]
    assert adj.forms_label == "Comparative / Superlative"
    assert adj.article_aux == "—"


def test_confidence_and_dates(workbook_path):
    cards = {c.german: c for c in load_cards(str(workbook_path)).cards}
    assert cards["Stift"].confidence == 1
    assert cards["Bericht"].confidence == 3
    assert cards["werden"].confidence is None          # blank cell -> ungraded
    assert cards["riesig"].confidence == 5             # 7 clamped into range
    assert cards["Stift"].date_added == date(2026, 9, 3)


def test_write_back_address_is_excel_row(workbook_path):
    cards = {c.german: c for c in load_cards(str(workbook_path)).cards}
    # Header is row 1; first data row is 2, second is 3.
    assert (cards["Stift"].sheet, cards["Stift"].row) == ("School & Office", 2)
    assert (cards["Bericht"].sheet, cards["Bericht"].row) == ("School & Office", 3)


def test_stats(workbook_path):
    data = load_cards(str(workbook_path))
    by_topic = {s.topic: s for s in data.stats(difficult_threshold=2)}
    school = by_topic["School & Office"]
    assert school.count == 2 and school.graded == 2
    assert school.avg_confidence == pytest.approx(2.0)
    assert school.difficult == 1  # only Stift (conf 1) is <= 2


def test_missing_file():
    with pytest.raises(WorkbookError):
        load_cards("/no/such/file.xlsx")


@pytest.mark.parametrize(
    "raw,expected",
    [(None, None), ("", None), ("  ", None), (3, 3), (3.0, 3), ("4", 4),
     (0, 1), (7, 5), ("nope", None)],
)
def test_coerce_confidence(raw, expected):
    assert coerce_confidence(raw) == expected
