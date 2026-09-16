"""Persistent app configuration (reference-file path + review preferences).

The reference file is set once and reused on every launch: the path lives in
`config.json` next to the app. "Replacing" the reference is just pointing this
path at a different file -- no upload, no copy.

`config.json` is gitignored (it holds a machine-specific absolute path).
"""

from __future__ import annotations

import json
import os
from pathlib import Path

from .workbook import WorkbookError, load_cards

APP_DIR = Path(__file__).resolve().parent.parent
CONFIG_PATH = APP_DIR / "config.json"
BACKUP_DIR = APP_DIR / "backups"

# Defaults chosen so a first run is immediately usable:
#   direction "mixed"                -> random DE<->EN per card (user asked for switchable)
#   difficult_threshold 2           -> "needs work" band in the workbook's own 1-2/3-4/5 scheme
#   order "lowest_confidence_first"  -> spend review time where it helps most
#   order "weighted"                -> weakest words likeliest, but not fixed
#   session_size 20                 -> ~6 min for the first pass at 18 s/card, up
#                                      to ~24 min if every word climbs to 5 (a
#                                      word at 1 needs 4 passes under averaging);
#                                      also Anki's long-standing new-cards/day
DEFAULT_PREFS = {
    "direction": "mixed",             # "de_en" | "en_de" | "mixed"
    "difficult_threshold": 2,
    "order": "weighted",  # | "lowest_confidence_first" | "random" | "oldest_first"
    "repeat_until_mastered": False,
    "session_size": 20,
}


def load_config() -> dict:
    """Return the stored config, filling in any missing preference keys."""
    data: dict = {}
    if CONFIG_PATH.is_file():
        try:
            data = json.loads(CONFIG_PATH.read_text(encoding="utf-8"))
        except (json.JSONDecodeError, OSError):
            # A corrupt config must not brick the app; fall back to defaults and
            # leave the bad file in place for the user to inspect.
            data = {}
    prefs = {**DEFAULT_PREFS, **(data.get("prefs") or {})}
    return {"reference_path": data.get("reference_path"), "prefs": prefs}


def save_config(config: dict) -> None:
    payload = {
        "reference_path": config.get("reference_path"),
        "prefs": {**DEFAULT_PREFS, **(config.get("prefs") or {})},
    }
    CONFIG_PATH.write_text(json.dumps(payload, indent=2), encoding="utf-8")


def get_reference_path() -> str | None:
    path = load_config().get("reference_path")
    return path if path and os.path.isfile(path) else None


def set_reference_path(path: str) -> None:
    """Validate `path` is a usable reference workbook, then persist it.

    Raises ValueError with a user-facing message on any problem.
    """
    path = os.path.expanduser(path.strip().strip('"'))
    if not path:
        raise ValueError("No path given.")
    if not os.path.isfile(path):
        raise ValueError(f"Not a file: {path}")
    if not path.lower().endswith((".xlsx", ".xlsm")):
        raise ValueError("Reference file must be an .xlsx / .xlsm workbook.")
    try:
        load_cards(path)  # raises WorkbookError if it has no usable topic sheets
    except WorkbookError as exc:
        raise ValueError(str(exc)) from exc

    config = load_config()
    config["reference_path"] = os.path.abspath(path)
    save_config(config)


def update_prefs(**changes) -> dict:
    config = load_config()
    config["prefs"].update(changes)
    save_config(config)
    return config["prefs"]
