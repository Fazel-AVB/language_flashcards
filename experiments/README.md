# Experiments — what the tool actually does

Four executable notebooks that walk through the app end to end, with outputs already
committed so you can read them on GitHub without running anything.

| notebook | question it answers |
|---|---|
| [01 · Reading the workbook](01_reading_the_workbook.ipynb) | Which sheets count as vocabulary, how a row becomes a `Card`, how scores are normalised |
| [02 · Choosing what to study](02_choosing_what_to_study.ipynb) | How a session's deck is drawn, why weak words dominate it, how repeats adapt as you improve |
| [03 · Grading and writing back](03_grading_and_writeback.ipynb) | What a grade does to a score, a full simulated session through the app's own code, and how the result reaches your `.xlsx` without damaging it |
| [04 · Spaced repetition](04_spaced_repetition.ipynb) | How a grade sets the next review date (FSRS), why in-session repeats don't count twice, what the "Due now" deck draws, and where the schedule is stored |

## Running them

```bash
python -m pip install -e "..[notebooks]"
jupyter lab            # or: jupyter notebook
```

They import the `flashcards` package from the parent directory, so run them **from this
directory** (the path setup cell handles either, but this is the tested case).

## They do not touch your vocabulary

`data/` is gitignored — your real workbook is yours. Every notebook calls
`demo_workbook.make_demo_workbook()` to generate a throwaway 48-word workbook under
`_demo/` instead, rebuilt from scratch on each run. That is also why these notebooks work
on a fresh clone with no setup.

The demo workbook deliberately reproduces the quirks the app has to survive: column D/E
header *labels* that differ per topic sheet while positions stay fixed, a formula-driven
dashboard sheet that must never be rewritten, and a spread of scores including ungraded
words.

`_demo/` (including the backups the write-back makes) is gitignored.

## Note on notebooks 03 and 04

They run `flashcards.session.ReviewSession`, the object the app's server holds for a
session in progress, rather than re-implementing the loop. So the traces they print are
the app's own behaviour, not a description of it: if the loop changes, the notebooks change
with it. FSRS runs unfuzzed there, so the dates are the same on every run.
