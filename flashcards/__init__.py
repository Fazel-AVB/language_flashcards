"""Flashcards -- Excel-backed vocab drill with confirmed write-back.

Works for any language pair: each topic sheet names its own languages in cells
A1/B1 (see `models.LanguagePair`), so nothing here is specific to German.

Package layout:
  models    -- Card dataclass, LanguagePair, positional column contract
  workbook  -- read topic sheets -> Cards (read-only)
  session   -- deck building (filter + order), pure functions
  writeback -- surgical Confidence write into the .xlsx (zip/XML, backed up)
  config    -- persistent reference path + review preferences
"""
