"""Writing the FSRS columns M..R (writeback.apply_updates): text, numbers,
clearing, header cells and the bookkeeping of new cells."""

import shutil
import zipfile
from pathlib import Path

import openpyxl
import pytest

from flashcards import srs
from flashcards.workbook import load_cards
from flashcards.writeback import _edit_cell_value, apply_updates

REAL_WORKBOOK = Path(__file__).resolve().parent.parent / "data" / "German_learning.xlsx"


def _member(path, name):
    with zipfile.ZipFile(path) as z:
        return z.read(name).decode("utf-8")


CELLS = {"M": "2026-10-05T09:00:00Z", "N": "Review", "O": None, "P": 3.1234, "Q": 5.5, "R": "2026-10-02T09:00:00Z"}


def test_schedule_cells_round_trip(workbook_path, tmp_path):
    data = load_cards(str(workbook_path))
    apply_updates(str(workbook_path), {("School & Office", 2): {**CELLS, "L": 2.5},
                                       ("School & Office", 1): dict(srs.SRS_HEADERS)},
                  original_mtime=data.mtime, backup_dir=str(tmp_path / "b"))

    reloaded = load_cards(str(workbook_path))
    stift = next(c for c in reloaded.cards if c.term == "Stift")
    assert stift.confidence == 2.5
    assert stift.schedule.state == "Review" and stift.schedule.stability == 3.1234
    assert "School & Office" not in reloaded.sheets_missing_srs_headers
    assert reloaded.schedule_problems == []

    ws = openpyxl.load_workbook(workbook_path)["School & Office"]
    assert ws["M1"].value == "Due (FSRS)" and ws["M2"].value == "2026-10-05T09:00:00Z"
    assert ws["O2"].value is None


def test_text_is_escaped_and_inline(workbook_path, tmp_path):
    data = load_cards(str(workbook_path))
    apply_updates(str(workbook_path), {("Verbs", 2): {"N": "a < b & c"}},
                  original_mtime=data.mtime, backup_dir=str(tmp_path))
    xml = _member(workbook_path, "xl/worksheets/sheet3.xml")
    assert 't="inlineStr"' in xml and "a &lt; b &amp; c" in xml
    assert openpyxl.load_workbook(workbook_path)["Verbs"]["N2"].value == "a < b & c"


def test_none_clears_value_keeps_style():
    out = _edit_cell_value('<row r="2"><c r="O2" s="4"><v>1</v></c></row>', 2, "O", None, "S")
    assert '<c r="O2" s="4"/>' in out
    # Clearing a cell that does not exist changes nothing.
    xml = '<row r="2"><c r="L2"><v>1</v></c></row>'
    assert _edit_cell_value(xml, 2, "O", None, "S") == xml


def test_new_cells_style_spans_and_dimension():
    xml = ('<worksheet><dimension ref="A1:L3"/><sheetData>'
           '<row r="1" spans="1:12"><c r="L1" s="7" t="s"><v>0</v></c></row>'
           '<row r="2" spans="1:12"><c r="L2" s="9"><v>1</v></c></row>'
           '</sheetData></worksheet>')
    out = _edit_cell_value(xml, 1, "M", "Due (FSRS)", "S")
    assert '<c r="M1" s="7" t="inlineStr">' in out          # header copies L1's look
    assert '<row r="1" spans="1:13">' in out
    assert '<dimension ref="A1:M3"/>' in out
    out = _edit_cell_value(out, 2, "M", "2026-10-05T09:00:00Z", "S")
    assert '<c r="M2" t="inlineStr">' in out                # data cell: NOT the header style
    out = _edit_cell_value(out, 2, "P", 2.5, "S")
    assert out.index('r="M2"') < out.index('r="P2"')


@pytest.mark.skipif(not REAL_WORKBOOK.is_file(), reason="needs your own data/German_learning.xlsx")
def test_real_workbook_only_target_sheet_changes(tmp_path):
    """On the real file (dynamic arrays, add-in part): every other zip member stays byte-identical."""
    path = tmp_path / "real.xlsx"
    shutil.copy2(REAL_WORKBOOK, path)
    data = load_cards(str(path))
    card = data.cards[0]
    with zipfile.ZipFile(path) as z:
        before = {n: z.read(n) for n in z.namelist()}

    apply_updates(str(path), {(card.sheet, card.row): CELLS, (card.sheet, 1): dict(srs.SRS_HEADERS)},
                  original_mtime=data.mtime, backup_dir=str(tmp_path / "b"))

    with zipfile.ZipFile(path) as z:
        after = {n: z.read(n) for n in z.namelist()}
    changed = {n for n in before if before[n] != after[n]}
    assert set(after) == set(before)
    assert len(changed - {"xl/workbook.xml"}) == 1  # only the one topic sheet
    reloaded = {c.id: c for c in load_cards(str(path)).cards}
    assert reloaded[card.id].schedule.state == "Review"
    assert len(reloaded) == len(data.cards)
