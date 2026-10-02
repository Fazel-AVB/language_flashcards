"""Persistent app configuration (reference-file path + review preferences).

The reference file is set once and reused on every launch: the path lives in
config.json in the data folder (paths.config_path, ~/.flashcards by default).
"Replacing" the reference is just pointing this path at a different file -- no
upload, no copy.
"""

from __future__ import annotations

import json
import os

from . import paths
from .workbook import WorkbookError, load_cards

# Defaults chosen so a first run is immediately usable:
#   mode "due"                       -> what FSRS says needs review, then new words
#   direction "mixed"                -> random term<->native per card (switchable)
#   difficult_threshold 2            -> "needs work" band in the workbook's own 1-2/3-4/5 scheme
#   order "weighted"                 -> weakest words likeliest, but not fixed
#   session_size 20                  -> ~6 min for the first pass at 18 s/card, up
#                                       to ~24 min if every word climbs to 5 (a
#                                       word at 1 needs 4 passes under averaging);
#                                       also Anki's long-standing new-cards/day
DEFAULT_PREFS = {
    "mode": "due",  # | "topic" | "mix" | "difficult"
    "topics": [],
    "direction": "mixed",  # "term_to_native" | "native_to_term" | "mixed"
    "difficult_threshold": 2,
    "order": "weighted",  # | "lowest_confidence_first" | "random" | "oldest_first"
    "repeat_until_mastered": False,
    "session_size": 20,
}

# Pre-multilingual config.json files store language-coded directions. Migrated on
# read so an existing install does not hit an unknown-direction error on the first
# launch after the upgrade. The mapping is by role: German was the term language
# in the only workbook that existed when those values were written.
_LEGACY_DIRECTIONS = {"de_en": "term_to_native", "en_de": "native_to_term"}


def load_config() -> dict:
    """Return the stored config, filling in any missing preference keys."""
    data: dict = {}
    path = paths.config_path()
    if path.is_file():
        try:
            data = json.loads(path.read_text(encoding="utf-8"))
        except (json.JSONDecodeError, OSError):
            # A corrupt config must not brick the app; fall back to defaults and
            # leave the bad file in place for the user to inspect.
            data = {}
    prefs = {**DEFAULT_PREFS, **(data.get("prefs") or {})}
    prefs["direction"] = _LEGACY_DIRECTIONS.get(prefs["direction"], prefs["direction"])
    return {"reference_path": data.get("reference_path"), "prefs": prefs}


def save_config(config: dict) -> None:
    payload = {
        "reference_path": config.get("reference_path"),
        "prefs": {**DEFAULT_PREFS, **(config.get("prefs") or {})},
    }
    path = paths.config_path()
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(payload, indent=2), encoding="utf-8")


def get_reference_path() -> str | None:
    path = load_config().get("reference_path")
    return path if path and os.path.isfile(path) else None


def set_reference_path(path: str) -> str:
    """Validate `path` is a usable reference workbook, then persist it.

    Returns the stored absolute path. Raises ValueError with a user-facing
    message on any problem.
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
    return config["reference_path"]


def update_prefs(**changes) -> dict:
    config = load_config()
    config["prefs"].update(changes)
    save_config(config)
    return config["prefs"]
