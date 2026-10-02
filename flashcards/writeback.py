"""Surgical write-back of review results into the reference .xlsx.

WHY NOT openpyxl: the workbook's Overview / Flashcards / Card Pool sheets use
modern dynamic-array formulas (LET/LAMBDA), rich-value metadata, and carry an
Office web add-in part. openpyxl drops or mangles all of these on save. So this
module does the minimum possible: it opens the .xlsx as a zip, rewrites only the
specific cells it was given in the affected topic sheet XML, and copies every
other archive member byte-for-byte.

What it writes (see `apply_updates`): the Confidence value in column L and the
FSRS schedule in columns M..R (srs.py), plus those columns' header text in row 1
the first time. Numbers go in as `<v>`, text as an inline string
(t="inlineStr"), so sharedStrings.xml is never touched either.

The topic sheets are plain data (no formulas), so a value swap is safe. If a
target cell ever contains a formula, we abort rather than guess.

Guarantees:
  * A timestamped copy of the original is written to `backup_dir` before any change.
  * The save is atomic (temp file in the same directory, then os.replace).
  * If the file's mtime changed since it was loaded, nothing is written.
  * `fullCalcOnLoad` is set so Excel recomputes the dashboards on next open.
"""

from __future__ import annotations

import math
import os
import re
import shutil
import tempfile
import zipfile
from dataclasses import dataclass
from datetime import datetime
from xml.sax.saxutils import escape

from .models import (
    CONFIDENCE_COL,
    CONFIDENCE_MAX,
    CONFIDENCE_MIN,
    CONFIDENCE_STEP,
    confidence_to_cell,
)

WORKBOOK_XML = "xl/workbook.xml"

# A cell value: a number, text, or None to clear the cell (its style is kept).
CellValue = float | int | str | None


class WritebackError(RuntimeError):
    """A write-back precondition failed; the workbook was not modified."""


@dataclass
class WriteResult:
    path: str
    backup_path: str
    cells_written: int
    sheets_touched: list[str]


def apply_updates(
    path: str,
    updates: dict[tuple[str, int], dict[str, CellValue]],
    *,
    original_mtime: float,
    backup_dir: str,
) -> WriteResult:
    """Write cell values.

    `updates` maps (sheet_name, excel_row) -> {column letter: value}. Column L
    must hold a confidence in [1, 5] on the half-step grid (grades are averaged
    in, so 3.5 is legal). An empty dict is a no-op (no backup, no write).
    """
    updates = {key: cols for key, cols in updates.items() if cols}
    if not updates:
        return WriteResult(path=path, backup_path="", cells_written=0, sheets_touched=[])

    for (sheet, row), cols in updates.items():
        for col, value in cols.items():
            _validate(sheet, row, col, value)

    if not os.path.isfile(path):
        raise WritebackError(f"File not found: {path}")
    if abs(os.path.getmtime(path) - original_mtime) > 1e-6:
        raise WritebackError(
            "The workbook changed on disk since it was loaded. Reload it and review again "
            "so your session is based on the current file."
        )

    backup_path = _backup(path, backup_dir)

    with zipfile.ZipFile(path, "r") as zin:
        names = set(zin.namelist())
        sheet_paths = _sheet_path_map(zin)

        by_sheet: dict[str, list[tuple[int, dict[str, CellValue]]]] = {}
        for (sheet, row), cols in updates.items():
            if sheet not in sheet_paths:
                raise WritebackError(f"Sheet not found in workbook: {sheet!r}")
            by_sheet.setdefault(sheet, []).append((row, cols))

        replacements: dict[str, str] = {}
        for sheet, changes in by_sheet.items():
            member = sheet_paths[sheet]
            if member not in names:
                raise WritebackError(f"Missing sheet part: {member}")
            xml = zin.read(member).decode("utf-8")
            for row, cols in changes:
                # Column order, so each inserted cell lands after the previous one.
                for col in sorted(cols, key=_col_index):
                    xml = _edit_cell_value(xml, row, col, cols[col], sheet)
            replacements[member] = xml

        if WORKBOOK_XML in names:
            wb_xml = zin.read(WORKBOOK_XML).decode("utf-8")
            replacements[WORKBOOK_XML] = _set_full_calc_on_load(wb_xml)

    # On any failure above or here the original file is untouched (the rewrite
    # goes to a temp file first); the backup is the safety net either way.
    _rewrite_zip(path, replacements)

    return WriteResult(
        path=path,
        backup_path=backup_path,
        cells_written=sum(len(cols) for cols in updates.values()),
        sheets_touched=sorted(by_sheet),
    )


def apply_confidence_updates(
    path: str,
    updates: dict[tuple[str, int], float],
    *,
    original_mtime: float,
    backup_dir: str,
) -> WriteResult:
    """Write Confidence values only: (sheet, row) -> new confidence."""
    return apply_updates(
        path,
        {key: {CONFIDENCE_COL: value} for key, value in updates.items()},
        original_mtime=original_mtime,
        backup_dir=backup_dir,
    )


def _validate(sheet: str, row: int, col: str, value: CellValue) -> None:
    if not re.fullmatch(r"[A-Z]{1,3}", col):
        raise WritebackError(f"{sheet}!row {row}: bad column {col!r}")
    if col == CONFIDENCE_COL and row > 1:
        if not isinstance(value, (int, float)) or isinstance(value, bool):
            raise WritebackError(f"{sheet}!row {row}: confidence {value!r} is not a number")
        if not (CONFIDENCE_MIN <= value <= CONFIDENCE_MAX):
            raise WritebackError(f"{sheet}!row {row}: confidence {value} out of range 1..5")
        # Off-grid values mean a caller bypassed models.blend_confidence; refuse
        # rather than write a 2.8125 that no part of the app can produce.
        if abs(value / CONFIDENCE_STEP - round(value / CONFIDENCE_STEP)) > 1e-9:
            raise WritebackError(
                f"{sheet}!row {row}: confidence {value} is not a multiple of {CONFIDENCE_STEP}"
            )
    elif isinstance(value, float) and not math.isfinite(value):
        raise WritebackError(f"{sheet}!{col}{row}: {value} cannot be stored in a cell")


# --------------------------------------------------------------------------- #
# XML editing
# --------------------------------------------------------------------------- #

def _edit_cell_value(xml: str, row: int, col: str, value: CellValue, sheet: str) -> str:
    """Return `xml` with cell <col><row> set to `value` (number, text, or None = clear).

    Handles: cell present with <v>, present but empty, self-closing, or absent
    (inserted in column order). Refuses cells that carry a formula.
    """
    ref = f"{col}{row}"
    row_match = re.search(rf'(<row r="{row}"(?: [^>]*)?>)(.*?)(</row>)', xml, re.DOTALL)
    if not row_match:
        # A self-closing <row r="1"/> (no cells at all) is also possible but never
        # happens for a word row or a header; refuse rather than restructure.
        raise WritebackError(f"{sheet}!{ref}: row {row} not found in sheet XML")
    head, body, tail = row_match.group(1), row_match.group(2), row_match.group(3)

    cell_re = re.compile(
        rf'<c r="{ref}"(?P<attrs>(?: [^>]*?)?)(?:/>|>(?P<inner>.*?)</c>)', re.DOTALL
    )
    cm = cell_re.search(body)
    if cm:
        attrs = cm.group("attrs") or ""
        inner = cm.group("inner") or ""
        if "<f" in inner or "<f" in attrs:
            raise WritebackError(f"{sheet}!{ref} contains a formula; refusing to overwrite")
        # Drop any t="s"/t="str"/t="inlineStr": the new value sets its own type.
        attrs = re.sub(r'\s+t="[^"]*"', "", attrs)
        new_body = body[: cm.start()] + _cell_xml(ref, attrs, col, value) + body[cm.end():]
    else:
        if value is None:
            return xml  # clearing a cell that does not exist: nothing to do
        style = _style_for_new_cell(xml, body, col, row)
        new_body = _insert_cell(body, col, _cell_xml(ref, style, col, value))
        head = _widen_spans(head, _col_index(col))
        xml = _widen_dimension(xml, col, row)
        # The dimension edit may shift the row; find it again.
        row_match = re.search(rf'(<row r="{row}"(?: [^>]*)?>)(.*?)(</row>)', xml, re.DOTALL)

    return xml[: row_match.start()] + head + new_body + tail + xml[row_match.end():]


def _cell_xml(ref: str, attrs: str, col: str, value: CellValue) -> str:
    if value is None:
        return f'<c r="{ref}"{attrs}/>'
    if isinstance(value, str):
        # xml:space keeps leading/trailing spaces, which Excel would otherwise trim.
        return f'<c r="{ref}"{attrs} t="inlineStr"><is><t xml:space="preserve">{escape(value)}</t></is></c>'
    return f'<c r="{ref}"{attrs}><v>{_number_text(col, value)}</v></c>'


def _number_text(col: str, value: float | int) -> str:
    if col == CONFIDENCE_COL:
        return confidence_to_cell(value)  # '3' for 3.0, never '3.0' (see models)
    return f"{value:.10g}"


def _style_for_new_cell(full_xml: str, row_body: str, col: str, row: int) -> str:
    """Style attribute for a cell being created.

    Header row: copy the Confidence header's style (L1), so a new "Due (FSRS)"
    header looks like the headers next to it. Data rows: copy the style of
    another *data* cell in the same column, never the header's -- the header's
    bold fill on every schedule cell would look broken. No such cell: no style.
    """
    if row == 1:
        m = re.search(rf'<c r="{CONFIDENCE_COL}1"[^>]*?\bs="(\d+)"', row_body)
        return f' s="{m.group(1)}"' if m else ""
    for m in re.finditer(rf'<c r="{col}(\d+)"[^>]*?\bs="(\d+)"', full_xml):
        if m.group(1) != "1":
            return f' s="{m.group(2)}"'
    return ""


def _insert_cell(body: str, col: str, new_cell: str) -> str:
    """Insert `new_cell` at the correct column position within the row `body`."""
    target = _col_index(col)
    for cm in re.finditer(r'<c r="([A-Z]+)\d+"(?:[^>]*?)(?:/>|>.*?</c>)', body, re.DOTALL):
        if _col_index(cm.group(1)) > target:
            return body[: cm.start()] + new_cell + body[cm.start():]
    return body + new_cell


def _widen_spans(row_head: str, col_index: int) -> str:
    """Grow a row's spans="1:12" hint to cover a newly added column.

    Excel tolerates a stale hint, but other readers use it to size the row, so
    keep it honest. Multi-range spans ("1:3 5:9") are left alone.
    """
    m = re.search(r'spans="(\d+):(\d+)"', row_head)
    if not m or int(m.group(2)) >= col_index:
        return row_head
    return row_head[: m.start()] + f'spans="{m.group(1)}:{col_index}"' + row_head[m.end():]


def _widen_dimension(xml: str, col: str, row: int) -> str:
    """Grow <dimension ref="A1:L17"/> to include the new cell."""
    m = re.search(r'<dimension ref="([A-Z]+)(\d+)(?::([A-Z]+)(\d+))?"', xml)
    if not m:
        return xml
    c0, r0 = m.group(1), int(m.group(2))
    c1, r1 = (m.group(3), int(m.group(4))) if m.group(3) else (c0, r0)
    if _col_index(col) > _col_index(c1):
        c1 = col
    r1 = max(r1, row)
    return xml[: m.start()] + f'<dimension ref="{c0}{r0}:{c1}{r1}"' + xml[m.end():]


def _col_index(letters: str) -> int:
    n = 0
    for ch in letters:
        n = n * 26 + (ord(ch) - 64)
    return n


def _set_full_calc_on_load(workbook_xml: str) -> str:
    """Force a full recalculation on open so the dashboard sheets refresh."""
    if "<calcPr" in workbook_xml:
        def repl(m: re.Match) -> str:
            tag = m.group(0)
            if "fullCalcOnLoad" in tag:
                return re.sub(r'fullCalcOnLoad="[^"]*"', 'fullCalcOnLoad="1"', tag)
            if tag.endswith("/>"):
                return tag[:-2] + ' fullCalcOnLoad="1"/>'
            return tag[:-1] + ' fullCalcOnLoad="1">'

        return re.sub(r"<calcPr[^>]*/?>", repl, workbook_xml, count=1)

    # No <calcPr> at all: add a minimal one right after the sheet list.
    return workbook_xml.replace("</sheets>", '</sheets><calcPr calcId="0" fullCalcOnLoad="1"/>', 1)


# --------------------------------------------------------------------------- #
# Archive helpers
# --------------------------------------------------------------------------- #

def _sheet_path_map(zin: zipfile.ZipFile) -> dict[str, str]:
    """Map worksheet display name -> archive member path (e.g. 'xl/worksheets/sheet3.xml')."""
    wb = zin.read(WORKBOOK_XML).decode("utf-8")
    rels = zin.read("xl/_rels/workbook.xml.rels").decode("utf-8")

    rid_to_target: dict[str, str] = {}
    for rel in re.findall(r"<Relationship\b[^>]*>", rels):
        rid = re.search(r'Id="([^"]+)"', rel)
        tgt = re.search(r'Target="([^"]+)"', rel)
        if rid and tgt:
            rid_to_target[rid.group(1)] = tgt.group(1)

    out: dict[str, str] = {}
    for sheet in re.findall(r"<sheet\b[^>]*/>", wb):
        name = re.search(r'name="([^"]+)"', sheet)
        rid = re.search(r'r:id="([^"]+)"', sheet)
        if not (name and rid):
            continue
        target = rid_to_target.get(rid.group(1))
        if not target:
            continue
        target = target.lstrip("/")
        if not target.startswith("xl/"):
            target = "xl/" + target
        out[_xml_unescape(name.group(1))] = target
    return out


def _xml_unescape(text: str) -> str:
    return (
        text.replace("&amp;", "&")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", '"')
        .replace("&apos;", "'")
    )


def _rewrite_zip(path: str, replacements: dict[str, str]) -> None:
    """Rewrite the archive in place, substituting only `replacements`.

    Every other member is copied with its original compression and metadata so
    the file stays as close to byte-identical as possible.
    """
    directory = os.path.dirname(os.path.abspath(path))
    fd, tmp = tempfile.mkstemp(suffix=".xlsx", dir=directory)
    os.close(fd)
    try:
        with zipfile.ZipFile(path, "r") as zin, zipfile.ZipFile(tmp, "w") as zout:
            for info in zin.infolist():
                data = zin.read(info.filename)
                if info.filename in replacements:
                    data = replacements[info.filename].encode("utf-8")
                clone = zipfile.ZipInfo(info.filename, date_time=info.date_time)
                clone.compress_type = info.compress_type
                clone.external_attr = info.external_attr
                clone.internal_attr = info.internal_attr
                clone.create_system = info.create_system
                clone.flag_bits = info.flag_bits
                zout.writestr(clone, data)
        os.replace(tmp, path)
    finally:
        if os.path.exists(tmp):
            os.remove(tmp)


def _backup(path: str, backup_dir: str) -> str:
    os.makedirs(backup_dir, exist_ok=True)
    stem, ext = os.path.splitext(os.path.basename(path))
    # Microseconds: two writes within one second (a quick second session) must
    # not overwrite the first backup.
    stamp = datetime.now().strftime("%Y%m%d-%H%M%S-%f")
    dest = os.path.join(backup_dir, f"{stem}.{stamp}{ext}")
    shutil.copy2(path, dest)
    return dest
