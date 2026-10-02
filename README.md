# Language Flashcards

Review the vocabulary in your own Excel workbook with spaced repetition (FSRS, the algorithm
Anki uses), in your browser, entirely on your computer. Your grades are written back into the
same workbook, only after you confirm, so the workbook stays the one place your vocabulary lives.

Works for **any language pair**. Each workbook declares its pair in its header row, so the same
app drills German→English and English→Persian with no setting. See
[What the workbook needs](#what-the-workbook-needs).

![How Flashcards works: the desktop icon starts a local server and opens the page; the page's Start, Review and Summary screens talk to the server, which on Save writes the confidence and FSRS schedule into columns L to R of your workbook after a backup to ~/.flashcards; code updates come from GitHub with git pull and never touch your files](docs/architecture.svg)

## What it does

| Screen | Purpose |
|---|---|
| **Start** | How many words are due now, new, or scheduled later. Build a deck: *Due now* (the default), chosen topics, all topics, or difficult words, and pick the [exercise](#exercises). |
| **Review** | A flashcard, a multiple-choice question or a sentence to complete. Then **1–5** grades it; each button shows what it will do: the new confidence and the next review date. |
| **Summary** | Every graded word, old → new confidence and next review. **Save to workbook** (after a backup) or **Discard**. |

## Code and data are kept apart

| | Where | Updated by |
|---|---|---|
| **Code** | this git clone, e.g. `D:\Programs\language_flashcards` | `git pull` |
| **Your workbook** | wherever you keep it (OneDrive, Documents, …); you point the app at it once | the app, on **Save** (columns L–R only) |
| **Settings, backups, review log** | `~/.flashcards` (Windows: `C:\Users\<you>\.flashcards`) | the app |

So updating, re-cloning or deleting the clone never touches your vocabulary or settings. Set the
`FLASHCARDS_HOME` environment variable to use another folder. `flashcards where` prints the paths.

## Install (once)

Windows PowerShell; on macOS/Linux use `python3` and `source .venv/bin/activate`.
If a conda `(base)` environment is active, run `conda deactivate` first.

```
git clone https://github.com/Fazel-AVB/language_flashcards.git
cd language_flashcards
py -m venv .venv
.venv\Scripts\activate
python -m pip install -e .
flashcards use "C:\path\to\German_learning.xlsx"
flashcards shortcut
```

- The repository is private: the first `git clone` opens a GitHub sign-in in the browser (Git
  for Windows' credential manager) and remembers it.
- `-e` (editable) makes the installed program run the code in this folder, so a later
  `git pull` takes effect without reinstalling.
- `flashcards use` sets the workbook. You can also pick it in the app (**Workbook → Browse…**).
- `flashcards shortcut` puts a **Flashcards** icon on your desktop. Double-click it to open the
  app. (By hand instead: in `.venv\Scripts`, right-click `flashcards-gui.exe` → *Show more
  options* → *Send to* → *Desktop (create shortcut)*; then *Properties* → *Change Icon* → pick
  `flashcards\web\static\flashcards.ico` in the clone.)

## Update

```
cd D:\Programs\language_flashcards      # your clone
.venv\Scripts\activate
git pull
```

That's all for a normal change: only the changed files are downloaded, and your workbook and
settings are not touched. If `pyproject.toml` changed (new dependency or command), also run
`python -m pip install -e .`. It's harmless when nothing changed. If the app is open, press
**Quit** and start it again to load the new code.

### Coming from the Streamlit version (before 0.2)

1. Close the old app's console window.
2. `git pull` in the same folder you used before, then run the [Install](#install-once) lines
   from `py -m venv .venv` onward (skip `flashcards use`: your workbook is remembered). Streamlit
   is no longer needed.
3. Delete the old desktop shortcut (it pointed at `launch.vbs`, which is gone). Use the new one.

On its first start the app copies your old `config.json` (workbook path and preferences) from the
clone to `~/.flashcards` and says so on the page. Old backups stay in the clone's `backups`
folder; new ones go to `~/.flashcards\backups`.

## Use

Double-click the desktop icon, or run `flashcards` in the activated environment. The browser
opens on `http://127.0.0.1:8767`, which only this computer can reach. Starting it again while it
runs just reopens the page. To stop it, press **Quit**; it also stops by itself 10 minutes after
you close the tab. Errors are logged to `~/.flashcards/gui.log`.

A normal day: open the app, press **Start review** on *Due now*, grade each word, **Save to
workbook**. Words you answered well come back days or weeks later; words you missed come back
soon.

Keyboard on the Review screen: **Space** / **Enter** show the answer, **1–5** grade, **S** skips,
**P** pronounces the word. In exercises: **1–4** pick an option, **Enter** checks a typed answer
and then takes the proposed grade.

### Exercises

Pick one under **Exercise** when you build a deck:

| Exercise | You see | You do |
|---|---|---|
| **Flashcards** | the word (or its meaning) | think of the answer, **Show answer**, grade yourself |
| **Multiple choice** | the word (or its meaning) and four options | pick one (click or **1–4**) |
| **Fill in the blank** | the word's example sentence with a gap, plus its meaning and the translated example as hints | type the missing word, **Enter** |
| **Type the word** | the meaning and the word type | type the word, **Enter** (with or without its article: `die Kerze` and `Kerze` both count) |
| **Listening** | nothing written: the word is spoken (🔊 *Play again*, 🐢 *Slow*, **Ctrl+Space**), with its word type as a hint | type what you heard, **Enter**. Needs a voice for the word's language ([Pronunciation](#pronunciation)); without one the option is greyed out |
| **Sentence scramble** | the translated example, and the words of the example (column G) as shuffled tiles | tap the tiles in order (tap a placed one to put it back; **Backspace** undoes, **Enter** checks). Needs a sentence of 4–16 words, otherwise multiple choice |
| **Article (der/die/das)** | a German noun | pick **der / die / das** (click or **1–3**). Only nouns whose column D is exactly der, die or das are in this deck; the option is greyed out for a workbook with none |
| **Mixed** | any of the above, per card | |

The wrong options come from your own workbook, preferring words of the same type and topic,
so a noun is not picked out among verbs. The blank is cut from column G (*Example*): the word
is found even when inflected (`riesig` blanks *riesiges*) or written with placeholders
(`lash out at someone`). A word whose sentence doesn't contain it, or whose sentence is the
word itself, gets multiple choice instead.

Typed answers ignore case, spacing and punctuation, and accept `ae`/`oe`/`ue`/`ss` for
`ä`/`ö`/`ü`/`ß`. A missing accent or one typo counts as **almost**; in the scramble, two
neighbouring words swapped does. The scramble knows one right order, the one in your sentence:
another order that is also correct German counts as wrong, so grade that yourself.

**Grading stays yours.** After checking, the app proposes a grade (highlighted; **Enter**
takes it): right → 4, almost → 3, wrong → 1. A wrong article or a wrong word order proposes
2, not 1: those mistakes don't mean the word itself is forgotten. Press another number if you were lucky or it
was effortless. The grade then updates confidence and FSRS exactly as for a flashcard.

### Pronunciation

🔊 next to the word, its meaning and the examples reads them aloud. With *Pronounce
automatically* on, the word is spoken when it appears (or when you reveal it, if the card
showed the meaning). It uses your browser's built-in speech engine with the voices installed on
the computer: nothing to install, works offline.

- A grey 🔊 means this computer has no voice for that language. On Windows: *Settings → Time &
  language → Speech → Add voices* (German, English and many others are available), then
  reload the page. The **Pronunciation** panel on the start page shows which voice is used.
- Windows has no installable Persian voice. Ticking *Also use online voices* lets Edge use its
  online voices (Persian included). Those send the text to Microsoft (Edge) or Google
  (Chrome), so they are off by default.
- **Listening** plays the word even with *Pronounce automatically* off: there, the sound is
  the question. *Mixed* includes listening only when the browser has a voice for the word's
  language.
- The language is taken from the names in A1/B1 (`German` → `de`, …; list in
  `models.LANGUAGE_CODES`).

## Spaced repetition (FSRS)

Every word has **two scores**, both updated by each grade:

| | Column | What it is for |
|---|---|---|
| **Confidence** | L | Your averaged 1–5 self-rating. Feeds your workbook's dashboards, the *Difficult* deck and the weighting of new words. See [How grading works](#how-grading-works). |
| **Schedule** | M–R | FSRS state: when the word is due again. Decides what *Due now* shows. |

FSRS keeps, per word, a *stability* (days until your chance of recalling it drops to 90%) and a
*difficulty*, and sets the next review so you are tested just before you would forget. Its four
ratings map onto your five buttons:

| You press | 1 needs work | 2 shaky | 3 getting there | 4 solid | 5 mastered |
|---|---|---|---|---|---|
| FSRS rating | Again | Hard | Good | Good | Easy |

- **New words** go through two short learning steps (1 min, 10 min) before their first interval
  in days; *5 mastered* skips them.
- **Due means due today.** A word scheduled for 21:00 is offered from the start of that day, as
  in Anki. Learning steps (minutes) use the exact time.
- **Repeats in one session don't count twice.** With *keep repeating* on, a word may come round
  several times in one sitting. Each time moves its confidence, but FSRS only takes the first grade
  of the session, plus any grade given when FSRS itself has the word due again (a learning step
  after *Again*). Four "Easy" presses in a row are not four spaced recalls.
- **Due now** shows the due words first, the ones you are most likely to have forgotten at the
  top, then words never scheduled, up to *Words per session*. Words due later are left out.
- Grades are fuzzed by a few percent, so words learned on the same day don't all fall due on
  the same later day.
- Every saved grade is also appended to `~/.flashcards/reviews.csv`. The app doesn't read it
  yet; it is the history FSRS needs to fit its parameters to *your* memory later.

The schedule columns, added with their headers the first time a sheet gets one:

| col | header | value |
|---|---|---|
| M | `Due (FSRS)` | UTC time as text, e.g. `2026-10-15T09:20:00Z` |
| N | `State (FSRS)` | `Learning`, `Review` or `Relearning` |
| O | `Step (FSRS)` | position in the learning steps; blank in Review |
| P | `Stability (FSRS)` | days |
| Q | `Difficulty (FSRS)` | 1–10 |
| R | `Last review (FSRS)` | UTC time as text |

Times are text with a trailing `Z` so Excel never turns them into local dates. If you clear all
six cells of a row, the word starts again as new; a row the app can't read is listed on the Start
screen and treated as new.

## How the deck is drawn

A session is capped at **N unique words** (default 20) drawn from everything in scope. In the
topic, all-topics and difficult modes, and for the new words in *Due now*, the default `weighted`
order is random but biased toward weak words: a word's chance is proportional to
`(5.5 - confidence) ** 2`, so a word at 1 is **81x** likelier to be drawn than one at 5, and an
ungraded word outranks both. Measured over 500 decks of 20 from a 300-word library:

| score | % of library | % of deck | over/under |
|------:|-------------:|----------:|-----------:|
| 1 | 10.0% | 35.0% | 3.50x |
| 2 | 13.3% | 29.6% | 2.22x |
| 3 | 20.0% | 22.7% | 1.13x |
| 4 | 26.7% | 11.4% | 0.43x |
| 5 | 30.0% |  1.4% | 0.05x |

Biased, not exclusive: mastered words still surface occasionally. Pick another order if you
want deterministic behaviour.

**Why 20?** At ~18 s per card that is ~6 min for the first pass, and up to ~24 min if every word
climbs all the way to 5 with *keep repeating* on (under averaging a word at 1 needs four
passes). It is also Anki's long-standing default for new cards per day.

**Within a session**, with "keep repeating" on, there are two phases:

1. **First pass**: every drawn word is shown exactly once.
2. **Repeats**: words still below 5 come round again, re-drawn by the same weighting but on
   their *live* session confidence. A word that improves yields its turn to those that did not.
   A word is never shown twice in a row unless it is the last one left.

## How grading works

Your 1–5 grade is averaged with the confidence the word already has:

```
new = (old + grade) / 2          # ungraded word: new = grade
```

So a mastered word (5) that you blank on once drops to 3, not to 1, and a weak word (1) you
happen to nail once rises to 3, not to 5.

- **Scores are half-steps.** `3.5` is legal and is written to the cell as `3.5`. Results are
  snapped to the nearest `0.5` (ties round up), so repeated averaging never produces `2.8125`.
- **The column is no longer integer-only.** If your Confidence column is formatted with `0`
  decimal places, Excel will *display* `3.5` as `4`. Set its number format to `0.0` or General.
- **Repeats compound within a session**: `1` → grade 5 → `3` → grade 5 → `4` → `4.5` → `5`.
- **"Mastered" means the stored confidence reached 5**, not that you pressed 5.

## What the workbook needs

One schema, used by every workbook. Header on **row 1**, data from **row 2**, columns **A–L**
fixed (M–R are the app's [schedule](#spaced-repetition-fsrs)):

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
| M–R | `Due (FSRS)` … | written by the app; see above |

Columns S onward are ignored, so extra bookkeeping columns can live there.

**Cells A1 and B1 are the only headers the app reads as data.** They name the two languages and
are displayed verbatim, driving the page title, the direction selector, the card labels and
right-to-left rendering. So write `English`, not `English Term / Expression`. Everything else is
resolved by *position*: the header labels for columns D and E deliberately differ per sheet
(Article / Auxiliary / n-a; Plural / Verb forms / Comparative) and are shown only as captions.

A sheet is read as a topic sheet when A1 and B1 are both filled and L1 mentions confidence.
Dashboard sheets are skipped by name (anything matching *overview*, *dashboard*, *flashcards*,
*card pool*, *summary*, *stats*). The name check is not redundant: a "Card Pool" sheet with
`Topic` in A1 and `Confidence` in L1 satisfies the structural test while being a dynamic-array
dashboard.

Right-to-left languages (Persian, Arabic, Hebrew, Urdu, …) are detected from the language name
and rendered right to left, so Persian text and its punctuation do not scramble.

## Why write-back is done by hand, not openpyxl

The reference workbook uses dynamic-array formulas (LET/LAMBDA), rich-value metadata, and
carries an Office web add-in part. openpyxl drops or corrupts these on save.
`flashcards/writeback.py` instead opens the `.xlsx` as a zip and edits only the specific cells
of columns L–R of the affected topic sheets (numbers as values, text as inline strings, so even
`sharedStrings.xml` is untouched), copying every other archive member byte-for-byte. It sets
`fullCalcOnLoad` so Excel refreshes the dashboards on open, refuses to run if the file changed
on disk since the session started, and always backs up first. If the workbook is open in Excel,
Windows locks it: close it there and press Save again; your session waits.

## Commands

```
flashcards                    open the app (same as double-clicking the icon)
flashcards gui --port 8800    use another port; --no-browser to not open a tab
flashcards use FILE           set the vocabulary workbook
flashcards due                how many words are due now
flashcards where              where the workbook, settings, backups and review log are
flashcards shortcut           (re)create the desktop icon
flashcards --version
```

## Privacy

Nothing leaves your computer. (The one opt-in exception: *Also use online voices*, see
[Pronunciation](#pronunciation).) The app serves its page on `127.0.0.1` only, loads nothing from the
internet, and requires a random key (new at every start) on every request, so websites open in
your browser can't read your vocabulary or change your workbook.

## Project layout

```
pyproject.toml            package definition; installs the flashcards commands
flashcards/
  models.py               Card dataclass, LanguagePair, column/position mapping, grade averaging
  workbook.py             read topic sheets -> Cards (read-only), schedule included
  srs.py                  FSRS: grades -> due dates, the M-R cells, due status
  session.py              deck building and ReviewSession (first pass, repeats, grading)
  exercises.py            the exercises (choice, blank, type the word, listening, scramble, article)
  writeback.py            surgical cell writes into the .xlsx
  config.py               the workbook path and your deck preferences
  paths.py                where settings, backups and the review log live (~/.flashcards)
  service.py              what each button does (used by the web app, unit-tested)
  cli.py                  the flashcards command
  shortcut.py             the desktop shortcut with the icon (Windows)
  web/
    launcher.py           start the local server, open the browser, stop when idle
    app.py                the JSON API on 127.0.0.1, with host and key checks
    static/               the page: index.html, app.js, style.css, logo.svg, flashcards.ico
tests/                    pytest, no browser needed (one synthetic workbook per language pair)
experiments/              executable notebooks demonstrating the tool (see below)
docs/                     the overview figure and the icon, with the scripts that draw them
```

## Experiments

`experiments/` holds four notebooks that walk through the app end to end, with outputs committed
so they read like documentation:

1. [Reading the workbook](experiments/01_reading_the_workbook.ipynb): sheet detection,
   position-based column mapping, score normalisation.
2. [Choosing what to study](experiments/02_choosing_what_to_study.ipynb): the session cap, the
   weighted draw, and how repeats shift to weaker words as you improve.
3. [Grading and writing back](experiments/03_grading_and_writeback.ipynb): averaging, a full
   simulated session, and proof that the write-back leaves the rest of the .xlsx byte-identical.
4. [Spaced repetition](experiments/04_spaced_repetition.ipynb): how grades set review dates, why
   in-session repeats don't count twice, and what *Due now* draws.

They generate their own demo workbook, so they never touch your vocabulary.
`python -m pip install -e ".[notebooks]"` installs what they need.

## Tests

```
python -m pip install -e ".[dev]"
python -m pytest -q
```
