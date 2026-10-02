"""Where the app keeps its own files: one folder, separate from the code.

Default: ~/.flashcards (on Windows C:\\Users\\<you>\\.flashcards). Set the
FLASHCARDS_HOME environment variable to use another folder.

    ~/.flashcards/
      config.json    which workbook to use, and your review preferences
      backups/       a copy of the workbook from before every write-back
      reviews.csv    one line per grade you saved (for tuning FSRS later)
      gui.log        errors of the app, when started without a console

Your vocabulary workbook is NOT in here: it stays wherever you keep it (the
path is in config.json), and the app edits it in place.

Kept outside the git clone on purpose: `git pull`, a fresh clone or deleting
the clone never touches these files. Before this folder existed (the Streamlit
version) config.json and backups/ sat in the clone; adopt_legacy_config()
copies the config here once.
"""

from __future__ import annotations

import os
import shutil
from pathlib import Path


def home() -> Path:
    return Path(os.environ.get("FLASHCARDS_HOME") or Path.home() / ".flashcards").expanduser()


def config_path() -> Path:
    return home() / "config.json"


def backup_dir() -> Path:
    return home() / "backups"


def reviews_path() -> Path:
    return home() / "reviews.csv"


def log_path() -> Path:
    return home() / "gui.log"


def clone_dir() -> Path:
    # The folder holding the flashcards/ package. Only meaningful with an
    # editable install (pip install -e .), where __file__ is inside the clone.
    return Path(__file__).resolve().parent.parent


def legacy_config_path() -> Path:
    return clone_dir() / "config.json"


def adopt_legacy_config() -> Path | None:
    """Copy <clone>/config.json into the data folder if there is no config there yet.

    Runs at every start and does nothing once a config exists, so it can never
    overwrite newer settings. Copied, not moved: the old file stays as a backup.
    Returns the copied file, or None.
    """
    src, dst = legacy_config_path(), config_path()
    if dst.exists() or not src.is_file():
        return None
    dst.parent.mkdir(parents=True, exist_ok=True)
    shutil.copy2(src, dst)
    return src
