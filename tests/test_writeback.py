import os
import zipfile

import pytest

from flashcards.workbook import load_cards
from flashcards.writeback import (
    WritebackError,
    _edit_cell_value,
    _set_full_calc_on_load,
    apply_confidence_updates,
)


def _member(path, name):
    with zipfile.ZipFile(path) as z:
        return z.read(name).decode("utf-8")


def test_updates_only_target_cells_and_backs_up(workbook_path, tmp_path):
    data = load_cards(str(workbook_path))
    backup_dir = tmp_path / "backups"

    result = apply_confidence_updates(
        str(workbook_path),
        {("School & Office", 2): 4, ("Verbs", 2): 5},
        original_mtime=data.mtime,
        backup_dir=str(backup_dir),
    )

    assert result.cells_written == 2
    assert result.sheets_touched == ["School & Office", "Verbs"]
    assert os.path.isfile(result.backup_path)

    reloaded = {c.german: c for c in load_cards(str(workbook_path)).cards}
    assert reloaded["Stift"].confidence == 4
    assert reloaded["werden"].confidence == 5
    assert reloaded["Bericht"].confidence == 3  # untouched


def test_half_values_round_trip(workbook_path, tmp_path):
    """Averaged scores are half-steps; they must survive the zip/XML write."""
    data = load_cards(str(workbook_path))
    apply_confidence_updates(
        str(workbook_path), {("School & Office", 2): 3.5, ("Verbs", 2): 4.0},
        original_mtime=data.mtime, backup_dir=str(tmp_path),
    )
    reloaded = {c.german: c for c in load_cards(str(workbook_path)).cards}
    assert reloaded["Stift"].confidence == 3.5
    assert reloaded["werden"].confidence == 4.0
    # A whole number is written bare, never as "4.0" (see models.confidence_to_cell)
    assert "<v>4</v>" in _member(workbook_path, "xl/worksheets/sheet3.xml")


def test_off_grid_value_rejected(workbook_path, tmp_path):
    data = load_cards(str(workbook_path))
    with pytest.raises(WritebackError, match="not a multiple"):
        apply_confidence_updates(
            str(workbook_path), {("Verbs", 2): 2.8125},
            original_mtime=data.mtime, backup_dir=str(tmp_path),
        )


def test_dashboard_formula_is_preserved(workbook_path, tmp_path):
    data = load_cards(str(workbook_path))
    # Overview is sheet1.xml (created first).
    before = _member(workbook_path, "xl/worksheets/sheet1.xml")
    assert "COUNTA" in before

    apply_confidence_updates(
        str(workbook_path), {("School & Office", 2): 2},
        original_mtime=data.mtime, backup_dir=str(tmp_path),
    )
    after = _member(workbook_path, "xl/worksheets/sheet1.xml")
    assert after == before  # dashboard sheet byte-identical


def test_full_calc_on_load_is_set(workbook_path, tmp_path):
    data = load_cards(str(workbook_path))
    apply_confidence_updates(
        str(workbook_path), {("Verbs", 2): 2},
        original_mtime=data.mtime, backup_dir=str(tmp_path),
    )
    assert 'fullCalcOnLoad="1"' in _member(workbook_path, "xl/workbook.xml")


def test_mtime_guard(workbook_path, tmp_path):
    data = load_cards(str(workbook_path))
    with pytest.raises(WritebackError, match="changed on disk"):
        apply_confidence_updates(
            str(workbook_path), {("Verbs", 2): 2},
            original_mtime=data.mtime - 5, backup_dir=str(tmp_path),
        )


def test_out_of_range_rejected(workbook_path, tmp_path):
    data = load_cards(str(workbook_path))
    with pytest.raises(WritebackError, match="out of range"):
        apply_confidence_updates(
            str(workbook_path), {("Verbs", 2): 9},
            original_mtime=data.mtime, backup_dir=str(tmp_path),
        )


def test_empty_updates_is_noop(workbook_path, tmp_path):
    data = load_cards(str(workbook_path))
    backup_dir = tmp_path / "backups"
    result = apply_confidence_updates(
        str(workbook_path), {}, original_mtime=data.mtime, backup_dir=str(backup_dir)
    )
    assert result.cells_written == 0
    assert not backup_dir.exists()  # no backup dir created for a no-op


def test_edit_cell_value_variants():
    # present with value
    assert '<v>4</v>' in _edit_cell_value(
        '<row r="2"><c r="L2" s="6"><v>1</v></c></row>', 2, "L", 4, "S")
    # half value written as "3.5"; whole float written bare, not "4.0"
    assert '<v>3.5</v>' in _edit_cell_value(
        '<row r="2"><c r="L2" s="6"><v>1</v></c></row>', 2, "L", 3.5, "S")
    assert '<v>4</v>' in _edit_cell_value(
        '<row r="2"><c r="L2" s="6"><v>1</v></c></row>', 2, "L", 4.0, "S")
    # self-closing
    out = _edit_cell_value('<row r="2"><c r="L2" s="6"/></row>', 2, "L", 3, "S")
    assert '<c r="L2" s="6"><v>3</v></c>' in out
    # string-typed cell: t="s" must be dropped
    out = _edit_cell_value('<row r="2"><c r="L2" t="s"><v>0</v></c></row>', 2, "L", 2, "S")
    assert 't="s"' not in out and "<v>2</v>" in out
    # absent cell inserted after K
    out = _edit_cell_value('<row r="2"><c r="K2"><v>1</v></c></row>', 2, "L", 5, "S")
    assert out.index("K2") < out.index("L2")
    # formula cell refused
    with pytest.raises(WritebackError, match="formula"):
        _edit_cell_value('<row r="2"><c r="L2"><f>A2+1</f><v>3</v></c></row>', 2, "L", 4, "S")


def test_set_full_calc_on_load_paths():
    assert _set_full_calc_on_load('<calcPr calcId="1"/>') == '<calcPr calcId="1" fullCalcOnLoad="1"/>'
    # no <calcPr> at all -> one is inserted after the sheet list
    out = _set_full_calc_on_load("<workbook><sheets></sheets></workbook>")
    assert '<calcPr calcId="0" fullCalcOnLoad="1"/>' in out
    # existing attribute is flipped, not duplicated
    assert _set_full_calc_on_load('<calcPr calcId="1" fullCalcOnLoad="0"/>') == '<calcPr calcId="1" fullCalcOnLoad="1"/>'
