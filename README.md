# German Flashcards

A local flashcard app that drills vocabulary from an Excel workbook and writes
your self-graded confidence back into the same file — only after you confirm.

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
   - Order: lowest-confidence-first / random / oldest-first.
   - Direction: mixed (random German↔English per card) / fixed either way.
   - Optionally repeat cards graded below 5 until the deck clears.
3. **Review:** read the front, "Show answer", then set the new confidence 1–5.
   Every pass re-grades the word, so repeating the deck keeps the number current.
4. **Summary:** you see only the words whose confidence changed (`old → new`).
   "Confirm & write to Excel" makes a timestamped backup in `backups/`, then
   updates the `Confidence (1-5)` cells. "Discard" leaves the file untouched.

## What the workbook needs

Any sheet with `German` in column A and `Confidence (1-5)` in column L is treated
as a topic sheet. Column *positions* A–L are the contract; the header *labels*
for columns D and E may differ per sheet (Article / Auxiliary / n-a; Plural /
Verb forms / Comparative). `Overview`, `Flashcards` and `Card Pool` are ignored.

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
  models.py            Card dataclass + column/position mapping
  workbook.py          read topic sheets -> Cards (read-only)
  session.py           deck building: filter + order (pure functions)
  writeback.py         surgical Confidence write into the .xlsx
  config.py            persistent reference path + preferences
tests/                 pytest suite (synthetic workbook fixture)
```

## Tests

```
pytest
```
