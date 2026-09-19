# Flashcards

A local flashcard app that drills vocabulary from an Excel workbook and writes
your self-graded confidence back into the same file — only after you confirm.

Works for **any language pair**. Each workbook declares its own pair in the
header row, so the same app drills German→English and English→Persian with no
code change and no per-file setting. See "What the workbook needs" below.

## Setup (once)

```
pip install -r requirements.txt
```

## Run

- **Windows:** double-click `launch.vbs` (no console) or `launch.bat`.
- **Any OS:** `streamlit run app.py`

The browser opens at `http://localhost:8501`.

## Using it

1. **Set the reference file** in the sidebar (Browse… or paste the full path).
   It is saved to `config.json` and reused on every launch. "Change reference
   file" repoints it; there is no per-session upload. Suggested spot for your
   own copy: `data/` (gitignored, so your vocab never gets committed).
2. **Build a deck:**
   - *Topic-wise* — pick one or more topic sheets.
   - *Mix* — every topic.
   - *Difficult words only* — confidence ≤ a threshold you set (ungraded words
     are not "difficult" yet, so they're excluded from this mode).
   - **Words per session** — the deck is capped at this many *unique* words
     (default 20). See "How the deck is drawn" below.
   - Order: weighted (default) / lowest-confidence-first / random / oldest-first.
   - Direction: mixed (random target↔native per card) / fixed either way.
   - Optionally repeat cards graded below 5 until the deck clears.
3. **Review:** read the front, "Show answer", then grade yourself 1–5. The grade
   is **averaged** with the word's current score, not substituted for it — see
   "How grading works" below. Each button shows the resulting score on hover.
4. **Summary:** you see only the words whose confidence changed (`old → new`).
   "Confirm & write to Excel" makes a timestamped backup in `backups/`, then
   updates the `Confidence (1-5)` cells. "Discard" leaves the file untouched.

## How the deck is drawn

A session is capped at **N unique words** (default 20) drawn from everything in
scope. With the default `weighted` order the draw is random but biased toward
weak words: a word's chance is proportional to `(5.5 - score) ** 2`, so a word at
1 is **81x** likelier to be drawn than one at 5, and an ungraded word outranks
both. Measured over 500 decks of 20 from a 300-word library:

| score | % of library | % of deck | over/under |
|------:|-------------:|----------:|-----------:|
| 1 | 10.0% | 35.0% | 3.50x |
| 2 | 13.3% | 29.6% | 2.22x |
| 3 | 20.0% | 22.7% | 1.13x |
| 4 | 26.7% | 11.4% | 0.43x |
| 5 | 30.0% |  1.4% | 0.05x |

Biased, not exclusive — mastered words still surface occasionally, which is what
keeps them mastered. Pick a different order in the sidebar if you want the old
deterministic behaviour.

**Why 20?** At ~18 s per card that is ~6 min for the first pass, and up to ~24 min
if every word climbs all the way to 5 (under averaging a word at 1 needs four
passes). It is also Anki's long-standing default for new cards per day. Drop to
10 for a daily habit, raise it for cram sessions.

**Within a session**, with "keep repeating" on, there are two phases:

1. **First pass** — every drawn word is shown exactly once. Capping at 20 means
   you really do see all 20.
2. **Repeats** — words still below 5 come round again, re-drawn by the same
   weighting but on their *live* session scores. So when a word improves its
   weight drops immediately and the words that did not improve take over the
   remaining repeats. A word is never shown twice in a row unless it is the last
   one left.

## How grading works

Your 1–5 grade is averaged with the score the word already has:

```
new = (old + grade) / 2          # ungraded word: new = grade
```

So a mastered word (5) that you blank on once drops to 3, not to 1, and a weak
word (1) you happen to nail once rises to 3, not to 5. One bad day cannot erase a
word's history and one lucky guess cannot mark it as learned.

Consequences worth knowing:

- **Scores are half-steps.** `3.5` is a legal value and is written to the Excel
  cell as `3.5`. Results are snapped to the nearest `0.5` (ties round up), so
  repeated averaging never produces `2.8125`.
- **The column is no longer integer-only.** If your Confidence column is
  formatted with `0` decimal places, Excel will *display* `3.5` as `4` while
  storing `3.5`. Set the column's number format to `0.0` (or General) to see it.
- **Repeats compound within a session.** With "keep repeating cards below 5" on,
  the second grade averages against the value the first one produced, so drilling
  a word repeatedly converges: `1` → grade 5 → `3` → grade 5 → `4` → `4.5` → `5`.
- **"Mastered" means the stored score reached 5**, not that you pressed 5. A
  single 5 on a weak word leaves it below 5, so it comes round again.

## What the workbook needs

One schema, used by every workbook. Header on **row 1**, data from **row 2**,
columns **A–L** fixed:

| col | header | notes |
|---|---|---|
| A | *target language name* | e.g. `German`, `English`. **A bare language name** |
| B | *native language name* | e.g. `English`, `Persian`. **A bare language name** |
| C | `Word type` | |
| D | `Article / Auxiliary` | leave blank for languages without articles |
| E | `Forms` | plural / verb forms / comparative, per sheet |
| F | `Pronunciation` | IPA |
| G | `Example (target)` | |
| H | `Example (native)` | |
| I | `Synonyms / Related` | |
| J | `Notes` | |
| K | `Date added` | |
| L | `Confidence (1-5)` | the write-back anchor |

Columns M onward are ignored, so extra bookkeeping columns can live there.

**Cells A1 and B1 are the only headers the app reads as data.** They name the two
languages and are displayed verbatim, driving the page title, the direction
selector, the card labels and right-to-left rendering. So write `English`, not
`English Term / Expression`. Everything else is resolved by *position*: the header
labels for columns D and E deliberately differ per sheet (Article / Auxiliary /
n-a; Plural / Verb forms / Comparative) and are shown only as captions.

A sheet is read as a topic sheet when A1 and B1 are both filled and L1 mentions
confidence. Dashboard sheets are skipped by name (anything matching *overview*,
*dashboard*, *flashcards*, *card pool*, *summary*, *stats*). The name check is not
redundant: a "Card Pool" sheet with `Topic` in A1 and `Confidence` in L1 satisfies
the structural test while being a dynamic-array dashboard.

Right-to-left languages (Persian, Arabic, Hebrew, Urdu, …) are detected from the
language name and rendered with `dir="rtl"`, so Persian text and its punctuation
do not scramble against Latin script.

## Why write-back is done by hand, not openpyxl

The reference workbook uses dynamic-array formulas (LET/LAMBDA), rich-value
metadata, and carries an Office web add-in part. openpyxl drops or corrupts these
on save. `flashcards/writeback.py` instead opens the `.xlsx` as a zip and edits
only the specific `<v>` values in the Confidence column of the affected topic
sheet XML, copying every other archive member byte-for-byte. It sets
`fullCalcOnLoad` so Excel refreshes the dashboards on open, refuses to run if the
file changed on disk since it was loaded, and always backs up first.

## Layout

```
app.py                 Streamlit UI + session state
data/                  your workbook lives here (gitignored)
flashcards/
  models.py            Card dataclass, LanguagePair, column/position mapping, grade averaging
  workbook.py          read topic sheets -> Cards (read-only)
  session.py           deck building: filter, weighted draw, repeat picker
  writeback.py         surgical Confidence write into the .xlsx
  config.py            persistent reference path + preferences
tests/                 pytest suite (one synthetic workbook per language pair)
experiments/           executable notebooks demonstrating the tool (see below)
```

## Experiments

`experiments/` holds three notebooks that walk through the app end to end, with outputs
committed so they read like documentation:

1. [Reading the workbook](experiments/01_reading_the_workbook.ipynb) — sheet detection,
   position-based column mapping, score normalisation.
2. [Choosing what to study](experiments/02_choosing_what_to_study.ipynb) — the session cap,
   the weighted draw, and how repeats shift to weaker words as you improve.
3. [Grading and writing back](experiments/03_grading_and_writeback.ipynb) — averaging, a
   full simulated session driven through `app.py` itself, and proof that the write-back
   leaves the rest of the .xlsx byte-identical.

They generate their own demo workbook, so they never touch `data/`.

## Tests

```
pytest
```
