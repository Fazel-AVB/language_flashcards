"""German flashcards -- Excel-backed vocab drill with confirmed write-back.

Package layout:
  models    -- Card dataclass + header/position mapping
  workbook  -- read topic sheets -> Cards (read-only)
  session   -- deck building (filter + order), pure functions
  writeback -- surgical Confidence write into the .xlsx (zip/XML, backed up)
  config    -- persistent reference path + review preferences
"""
