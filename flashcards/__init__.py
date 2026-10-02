"""Flashcards: review an Excel vocabulary workbook with spaced repetition (FSRS).

Works for any language pair: each topic sheet names its own languages in cells
A1/B1 (see `models.LanguagePair`). The code lives in this package; your
settings and backups live in ~/.flashcards (see paths.py); your workbook stays
wherever you keep it.

Package layout:
  models    -- Card dataclass, LanguagePair, positional column contract
  workbook  -- read topic sheets -> Cards (read-only)
  srs       -- FSRS schedule: grades -> due dates, stored in columns M..R
  session   -- deck building and the review session, pure
  writeback -- surgical cell writes into the .xlsx (zip/XML, backed up)
  config    -- persistent reference path + review preferences
  paths     -- where settings, backups and the review log live
  service   -- what each button does (used by web/, unit-tested)
  web/      -- the local Flask server and the page
  cli       -- the `flashcards` command
"""

from importlib.metadata import PackageNotFoundError, version

try:
    __version__ = version("language-flashcards")
except PackageNotFoundError:  # running from a clone without `pip install -e .`
    __version__ = "0+unknown"
