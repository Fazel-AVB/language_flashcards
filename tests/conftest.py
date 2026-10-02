"""Shared fixtures: synthetic workbooks that mirror the real files' quirks.

Two fixtures, one per language pair actually in use, because the app must not
hard-code either:

  `workbook_path`       German/English, the shape of data/German_learning.xlsx
  `en_fa_workbook_path` English/Persian, the shape of
                        data/English_Vocabulary_By_Topic_v2.xlsx

Both deliberately reproduce quirks the loader has to survive: header variants
across topic sheets (column D and E labels differ), a formula-bearing dashboard
sheet that must never be rewritten, and a "Card Pool" sheet that PASSES the
structural header test and is only excluded by name (see
`models.NON_TOPIC_NAME_RE`). Do not drop that sheet from the fixture: it is the
regression guard for the one case where structure alone is not enough.
"""

from __future__ import annotations

import sys
from datetime import datetime
from pathlib import Path

import openpyxl
import pytest

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))


@pytest.fixture(autouse=True)
def fc_home(tmp_path, monkeypatch) -> Path:
    """Point the app's data folder (config.json, backups, review log) at a temp dir.

    Autouse, so no test can read or overwrite the real ~/.flashcards.
    """
    home = tmp_path / "fc_home"
    monkeypatch.setenv("FLASHCARDS_HOME", str(home))
    return home

# A1/B1 hold the bare language names; that is how the app learns the pair.
STD_HEADERS = [
    "German", "English", "Word type", "Article", "Plural", "Pronunciation",
    "Example (German)", "Example (English)", "Synonyms / Related", "Notes",
    "Date added", "Confidence (1-5)",
]
VERB_HEADERS = STD_HEADERS[:3] + ["Article / Auxiliary", "Plural / Verb forms"] + STD_HEADERS[5:]
ADJ_HEADERS = STD_HEADERS[:3] + ["Article (n/a)", "Comparative / Superlative"] + STD_HEADERS[5:]

EN_FA_HEADERS = [
    "English", "Persian", "Word Type", "Article", "Plural / Forms",
    "Pronunciation (IPA)", "Example (English)", "Example (Persian)",
    "Synonyms / Related", "Notes", "Date Added", "Confidence (1-5)",
]


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

    # Passes is_topic_header (A1 filled, B1 filled, L1 says "Confidence") yet is a
    # dynamic-array dashboard. Only the name check keeps it out. See module docstring.
    pool = wb.create_sheet("Card Pool")
    pool.append(["Topic", "German", "English", "Word type", "Article / Aux",
                 "Plural / Forms", "Pronunciation", "Example (DE)", "Example (EN)",
                 "Synonyms", "Notes", "Confidence"])

    path = tmp_path / "vocab.xlsx"
    wb.save(path)
    return path


@pytest.fixture
def en_fa_workbook_path(tmp_path: Path) -> Path:
    """English/Persian workbook: a different pair, an RTL native language, and no
    articles (column D is present but empty, as in the real file)."""
    wb = openpyxl.Workbook()

    # Named to also exercise the dashboard name pattern, not just the literal
    # "Overview" the German file uses.
    dash = wb.active
    dash.title = "Overview & Dashboard"
    dash["A1"] = "Topic / Category"
    dash["A2"] = "=COUNTA('Emotions & Mind'!A2:A44)"

    emo = wb.create_sheet("Emotions & Mind")
    emo.append(EN_FA_HEADERS)
    emo.append(["charm", "جذابیت", "Noun", None, "charms", "/tʃɑːm/",
                "His natural charm put everyone at ease.",
                "جذابیت طبیعی او همه را راحت کرد.",
                "appeal, charisma", None, datetime(2026, 9, 14), 1])
    emo.append(["composure", "خونسردی", "Noun", None, "— (uncountable)", "/kəmˈpəʊ.ʒər/",
                "She kept her composure.", "او خونسردی خود را حفظ کرد.",
                "calmness, poise", None, datetime(2026, 9, 14), 4])

    idioms = wb.create_sheet("Idioms & Sentences")
    idioms.append(EN_FA_HEADERS)
    idioms.append(["anew", "از نو", "Adverb", None, "—", "/əˈnjuː/",
                   "They started anew.", "آنها از نو شروع کردند.",
                   "afresh, again", None, datetime(2026, 9, 14), None])

    path = tmp_path / "en_fa_vocab.xlsx"
    wb.save(path)
    return path
