"""Regenerate the test workbooks: python make_fixtures.py (needs openpyxl).

The same shapes as the desktop app's tests/conftest.py: header variants per
sheet, a formula dashboard that must survive write-back, and a "Card Pool"
sheet that passes the structural header test and is only skipped by name.
"""
from datetime import datetime
import openpyxl

STD = ["German", "English", "Word type", "Article", "Plural", "Pronunciation",
       "Example (German)", "Example (English)", "Synonyms / Related", "Notes",
       "Date added", "Confidence (1-5)"]
VERB = STD[:3] + ["Article / Auxiliary", "Plural / Verb forms"] + STD[5:]
ADJ = STD[:3] + ["Article (n/a)", "Comparative / Superlative"] + STD[5:]
FA = ["English", "Persian", "Word Type", "Article", "Plural / Forms", "Pronunciation (IPA)",
      "Example (English)", "Example (Persian)", "Synonyms / Related", "Notes", "Date Added",
      "Confidence (1-5)"]

wb = openpyxl.Workbook()
dash = wb.active
dash.title = "Overview"
dash["A1"] = "Topic"
dash["A2"] = "=COUNTA('Verbs'!A:A)"
school = wb.create_sheet("School & Office")
school.append(STD)
school.append(["Stift", "pen", "Noun", "der", "die Stifte", "[ʃtɪft]", "Ich schreibe mit einem Stift.",
               "I write with a pen.", "der Kuli", "umbrella term", datetime(2026, 9, 3), 1])
school.append(["Bericht", "report", "Noun", "der", "die Berichte", "[bəˈʁɪçt]", "einen Bericht schreiben",
               "write a report", "berichten", "über + acc", datetime(2026, 9, 5), 3])
verbs = wb.create_sheet("Verbs")
verbs.append(VERB)
verbs.append(["werden", "to become", "Verb", "sein", "wird – wurde – ist geworden", "[ˈveːɐ̯dn̩]",
              "Sie will Ärztin werden.", "She wants to become a doctor.", "entstehen",
              "aux + main + passive", datetime(2026, 9, 3), None])
adj = wb.create_sheet("Adjectives & Adverbs")
adj.append(ADJ)
adj.append(["riesig", "huge", "Adjective / Adverb", "—", "riesiger – am riesigsten", "[ˈʁiːzɪç]",
            "ein riesiges Haus", "a huge house", "enorm", "from der Riese", datetime(2026, 9, 3), 7])
pool = wb.create_sheet("Card Pool")
pool.append(["Topic", "German", "English", "Word type", "Article / Aux", "Plural / Forms",
             "Pronunciation", "Example (DE)", "Example (EN)", "Synonyms", "Notes", "Confidence"])
wb.save("vocab_de.xlsx")

wb = openpyxl.Workbook()
dash = wb.active
dash.title = "Overview & Dashboard"
dash["A1"] = "Topic / Category"
dash["A2"] = "=COUNTA('Emotions & Mind'!A2:A44)"
emo = wb.create_sheet("Emotions & Mind")
emo.append(FA)
emo.append(["charm", "جذابیت", "Noun", None, "charms", "/tʃɑːm/", "His natural charm put everyone at ease.",
            "جذابیت طبیعی او همه را راحت کرد.", "appeal, charisma", None, datetime(2026, 9, 14), 1])
emo.append(["composure", "خونسردی", "Noun", None, "— (uncountable)", "/kəmˈpəʊ.ʒər/", "She kept her composure.",
            "او خونسردی خود را حفظ کرد.", "calmness, poise", None, datetime(2026, 9, 14), 4])
emo.append([None] * 12)  # a blank row in the middle: rows must keep their real numbers
emo.append(["anew", "از نو", "Adverb", None, "—", "/əˈnjuː/", "They started anew.", "آنها از نو شروع کردند.",
            "afresh, again", None, datetime(2026, 9, 14), 2.5,
            "2026-10-15T09:20:00Z", "Review", None, 4.2, 5.1, "2026-10-05T09:20:00Z"])
emo.append(["broken", "شکسته", "Adjective", None, None, None, None, None, None, None, None, None,
            "not a date", "Review"])
wb.save("vocab_en_fa.xlsx")
