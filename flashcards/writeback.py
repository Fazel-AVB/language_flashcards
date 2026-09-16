"""Surgical write-back of Confidence values into the reference .xlsx.

WHY NOT openpyxl: the workbook's Overview / Flashcards / Card Pool sheets use
modern dynamic-array formulas (LET/LAMBDA), rich-value metadata, and carry an
Office web add-in part. openpyxl drops or mangles all of these on save. So this
module does the minimum possible: it opens the .xlsx as a zip, rewrites only the
specific `<v>` values in the Confidence column of the affected topic sheet XML,
and copies every other archive member byte-for-byte.

The topic sheets are plain data (no formulas), so a numeric-value swap is safe.
If a target cell ever contains a formula or shared-string, we abort rather than
guess.

Guarantees:
  * A timestamped copy of the original is written to `backup_dir` before any change.
  * The save is atomic (temp file in the same directory, then os.replace).
  * If the file's mtime changed since it was loaded, nothing is written.
  * `fullCalcOnLoad` is set so Excel recomputes the dashboards on next open.
"""

from __future__ import annotations

import os
import re
import shutil
import tempfile
import zipfile
from dataclasses import dataclass
from datetime import datetime

from .models import CONFIDENCE_COL, CONFIDENCE_MAX, CONFIDENCE_MIN

WORKBOOK_XML = "xl/workbook.xml"


class WritebackError(RuntimeError):
    """A write-back precondition failed; the workbook was not modified."""


@dataclass
class WriteResult:
    path: str
    backup_path: str
    cells_written: int
    sheets_touched: list[str]


def apply_confidence_updates(
    path: str,
    updates: dict[tuple[str, int], int],
    *,
    original_mtime: float,
    backup_dir: str,
) -> WriteResult:
    """Write new confidence values.

    `updates` maps (sheet_name, excel_row) -> new confidence (1..5). An empty
    dict is a no-op (no backup, no write).
    """
    if not updates:
        return WriteResult(path=path, backup_path="", cells_written=0, sheets_touched=[])

    for (sheet, row), value in updates.items():
        if not (CONFIDENCE_MIN <= value <= CONFIDENCE_MAX):
            raise WritebackError(f"{sheet}!row {row}: confidence {value} out of range 1..5")

    if not os.path.isfile(path):
        raise WritebackError(f"File not found: {path}")
    if abs(os.path.getmtime(path) - original_mtime) > 1e-6:
        raise WritebackError(
            "The workbook changed on disk since it was loaded. Reload it and review again "
            "so your session is based on the current file."
        )

    backup_path = _backup(path, backup_dir)

    try:
        with zipfile.ZipFile(path, "r") as zin:
            names = set(zin.namelist())
            sheet_paths = _sheet_path_map(zin)

            by_sheet: dict[str, list[tuple[int, int]]] = {}
            for (sheet, row), value in updates.items():
                if sheet not in sheet_paths:
                    raise WritebackError(f"Sheet not found in workbook: {sheet!r}")
                by_sheet.setdefault(sheet, []).append((row, value))

            replacements: dict[str, str] = {}
            for sheet, changes in by_sheet.items():
                member = sheet_paths[sheet]
                if member not in names:
                    raise WritebackError(f"Missing sheet part: {member}")
                xml = zin.read(member).decode("utf-8")
                for row, value in changes:
                    xml = _edit_cell_value(xml, row, CONFIDENCE_COL, value, sheet)
                replacements[member] = xml

            if WORKBOOK_XML in names:
                wb_xml = zin.read(WORKBOOK_XML).decode("utf-8")
                replacements[WORKBOOK_XML] = _set_full_calc_on_load(wb_xml)

        _rewrite_zip(path, replacements)
    except Exception:
        # Leave the original in place; the backup is the safety net either way.
        raise

    return WriteResult(
        path=path,
        backup_path=backup_path,
        cells_written=len(updates),
        sheets_touched=sorted(by_sheet),
    )


# --------------------------------------------------------------------------- #
# XML editing
# --------------------------------------------------------------------------- #

def _edit_cell_value(xml: str, row: int, col: str, value: int, sheet: str) -> str:
    """Return `xml` with cell <col><row> set to the integer `value`.

    Handles: cell present with <v>, present but empty, self-closing, or absent
    (inserted in column order). Refuses cells that carry a formula.
    """
    ref = f"{col}{row}"
    row_match = re.search(rf'(<row r="{row}"(?: [^>]*)?>)(.*?)(</row>)', xml, re.DOTALL)
    if not row_match:
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
        # Drop any t="s"/t="str"/t="inlineStr": a numeric grade must not stay typed as text.
        attrs = re.sub(r'\s+t="[^"]*"', "", attrs)
        new_cell = f'<c r="{ref}"{attrs}><v>{value}</v></c>'
        new_body = body[: cm.start()] + new_cell + body[cm.end():]
    else:
        new_body = _insert_cell(xml, body, ref, col, value)

    return xml[: row_match.start()] + head + new_body + tail + xml[row_match.end():]


def _insert_cell(full_xml: str, body: str, ref: str, col: str, value: int) -> str:
    """Insert a new <c> for `ref` at the correct column position within `body`."""
    style_match = re.search(rf'<c r="{col}\d+"[^>]*\bs="(\d+)"', full_xml)
    style = f' s="{style_match.group(1)}"' if style_match else ""
    new_cell = f'<c r="{ref}"{style}><v>{value}</v></c>'

    target = _col_index(col)
    for cm in re.finditer(r'<c r="([A-Z]+)\d+"(?:[^>]*?)(?:/>|>.*?</c>)', body, re.DOTALL):
        if _col_index(cm.group(1)) > target:
            return body[: cm.start()] + new_cell + body[cm.start():]
    return body + new_cell


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
    stamp = datetime.now().strftime("%Y%m%d-%H%M%S")
    dest = os.path.join(backup_dir, f"{stem}.{stamp}{ext}")
    shutil.copy2(path, dest)
    return dest
