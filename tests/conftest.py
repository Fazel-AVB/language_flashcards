"""Shared fixtures: a synthetic workbook that mirrors the real file's quirks.

Deliberately reproduces the header variants across topic sheets (column D and E
labels differ) and includes a formula-bearing dashboard sheet, so tests exercise
the position-based mapping and the "never touch dashboards" rule.
"""

from __future__ import annotations

import sys
from datetime import datetime
from pathlib import Path

import openpyxl
import pytest

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

STD_HEADERS = [
    "German", "English", "Word type", "Article", "Plural", "Pronunciation",
    "Example (German)", "Example (English)", "Synonyms / Related", "Notes",
    "Date added", "Confidence (1-5)",
]
VERB_HEADERS = STD_HEADERS[:3] + ["Article / Auxiliary", "Plural / Verb forms"] + STD_HEADERS[5:]
ADJ_HEADERS = STD_HEADERS[:3] + ["Article (n/a)", "Comparative / Superlative"] + STD_HEADERS[5:]


@pytest.fixture
def workbook_path(tmp_path: Path) -> Path:
    wb = openpyxl.Workbook()

    dash = wb.active
    dash.title = "Overview"
    dash["A1"] = "Topic"
    dash["A2"] = "=COUNTA('Verbs'!A:A)"  # must survive write-back untouched

    school = wb.create_sheet("School & Office")
    school.append(STD_HEADERS)
    school.append(["Stift", "pen", "Noun", "der", "die Stifte", "[ʃtɪft]",
                   "Ich schreibe mit einem Stift.", "I write with a pen.",
                   "der Kuli", "umbrella term", datetime(2026, 9, 3), 1])
    school.append(["Bericht", "report", "Noun", "der", "die Berichte", "[bəˈʁɪçt]",
                   "einen Bericht schreiben", "write a report",
                   "berichten", "über + acc", datetime(2026, 9, 5), 3])

    verbs = wb.create_sheet("Verbs")
    verbs.append(VERB_HEADERS)
    verbs.append(["werden", "to become", "Verb", "sein", "wird – wurde – ist geworden",
                  "[ˈveːɐ̯dn̩]", "Sie will Ärztin werden.", "She wants to become a doctor.",
                  "entstehen", "aux + main + passive", datetime(2026, 9, 3), None])

    adj = wb.create_sheet("Adjectives & Adverbs")
    adj.append(ADJ_HEADERS)
    adj.append(["riesig", "huge", "Adjective / Adverb", "—", "riesiger – am riesigsten",
                "[ˈʁiːzɪç]", "ein riesiges Haus", "a huge house",
                "enorm", "from der Riese", datetime(2026, 9, 3), 7])  # out-of-range on purpose

    pool = wb.create_sheet("Card Pool")
    pool.append(["Topic", "German"])  # dashboard-style, must be ignored

    path = tmp_path / "vocab.xlsx"
    wb.save(path)
    return path
