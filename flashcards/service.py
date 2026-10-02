"""What each button of the page does. Used by web/app.py; unit-tested without a browser.

One `Service` per running app. It holds the one review session in progress
(this is a single-user app on your own computer) and turns everything into
plain dicts the page can draw. The rules live in session.py, srs.py and
writeback.py; this module wires them to the workbook, the config and the
review log.

Stages the page moves through:
  setup   -> no session: pick a deck
  review  -> a card is showing
  summary -> the deck is done (or ended): confirm or discard the changes
  saved   -> written to the workbook; shows where the backup went
"""

from __future__ import annotations

import csv
import os
import random
import subprocess
import sys
import threading
from collections.abc import Callable
from datetime import datetime, timezone, tzinfo

from . import config, exercises, paths, srs
from .models import CONFIDENCE_MAX, CONFIDENCE_MIN, Card, format_confidence, is_rtl, language_code
from .session import DIRECTIONS, EXERCISES, MODES, ORDERS, ReviewSession, build_deck
from .workbook import WorkbookData, WorkbookError, load_cards
from .writeback import WritebackError, WriteResult, apply_updates

GRADE_LABELS = {
    1: "needs work",
    2: "shaky",
    3: "getting there",
    4: "solid",
    5: "mastered",
}
MAX_SESSION_SIZE = 500


class UserError(ValueError):
    """A problem the page shows to you as is (bad input, file changed, ...)."""


class Service:
    def __init__(
        self,
        *,
        now: Callable[[], datetime] | None = None,
        local_tz: tzinfo | None = None,
        fuzz: bool = True,
    ) -> None:
        """now / local_tz / fuzz exist for tests; the app uses the real clock,
        the computer's time zone and fuzzed FSRS intervals."""
        self._now = now or (lambda: datetime.now(timezone.utc))
        self._tz = local_tz
        self._fuzz = fuzz
        # The web server is threaded; two quick clicks must not grade twice
        # against the same card or interleave a write with a grade.
        self._lock = threading.RLock()
        self._session: ReviewSession | None = None
        self._data: WorkbookData | None = None  # the workbook as loaded when the session started
        self._saved: dict | None = None

    # ------------------------------------------------------------------ #
    # Reference file and overview
    # ------------------------------------------------------------------ #

    def overview(self) -> dict:
        """Everything the start page shows: the workbook, its topics, what is due."""
        cfg = config.load_config()
        prefs = cfg["prefs"]
        ref = config.get_reference_path()
        out: dict = {"reference": ref, "stored_reference": cfg["reference_path"], "prefs": prefs,
                     "data_folder": str(paths.home())}
        if not ref:
            return out
        try:
            data = load_cards(ref)
        except WorkbookError as exc:
            out["error"] = str(exc)
            return out

        now = self._now()
        threshold = int(prefs["difficult_threshold"])
        status = {c.id: srs.status(c.schedule, now, self._tz) for c in data.cards}
        topics = []
        for stat in data.stats(threshold):
            group = [c for c in data.cards if c.topic == stat.topic]
            topics.append({
                "topic": stat.topic,
                "words": stat.count,
                "graded": stat.graded,
                "avg_confidence": None if stat.avg_confidence is None else round(stat.avg_confidence, 2),
                "difficult": stat.difficult,
                "due": sum(status[c.id] == srs.DUE for c in group),
                "new": sum(status[c.id] == srs.NEW for c in group),
            })
        later = [c.schedule.due for c in data.cards if status[c.id] == srs.LATER]
        out.update(
            languages={"term": data.languages.term, "native": data.languages.native},
            language_codes={"term": language_code(data.languages.term),
                            "native": language_code(data.languages.native)},
            words=len(data.cards),
            article_words=sum(1 for c in data.cards if exercises.article_of(c)),
            topics=topics,
            due=sum(s == srs.DUE for s in status.values()),
            new=sum(s == srs.NEW for s in status.values()),
            later=len(later),
            next_due=srs.describe_due(min(later), now, self._tz) if later else None,
            language_conflicts=data.language_conflicts,
            schedule_problems=data.schedule_problems,
            modified=datetime.fromtimestamp(data.mtime).strftime("%Y-%m-%d %H:%M"),
        )
        return out

    def set_reference(self, path: str) -> dict:
        with self._lock:
            if self._session is not None:
                raise UserError("Finish or discard the current session before changing the workbook.")
            try:
                config.set_reference_path(path or "")
            except ValueError as exc:
                raise UserError(str(exc)) from exc
            self._saved = None
        return self.overview()

    # ------------------------------------------------------------------ #
    # Building a deck
    # ------------------------------------------------------------------ #

    def scope(self, params: dict) -> dict:
        """How many words a deck with these settings would draw from, and draw."""
        opts = _deck_options(params)
        data = self._load_reference()
        pool = self._build(data, opts, seed=0, limit=None)
        drawn = pool[: opts["size"]]
        now = self._now()
        return {
            "in_scope": len(pool),
            "drawn": len(drawn),
            "due": sum(srs.status(c.schedule, now, self._tz) == srs.DUE for c in drawn),
            "new": sum(c.schedule is None for c in drawn),
        }

    def start(self, params: dict) -> dict:
        opts = _deck_options(params)
        with self._lock:
            if self._session is not None:
                raise UserError("A session is already in progress.")
            data = self._load_reference()
            # The browser reports which languages it has a voice for (app.js deckParams).
            can_listen = language_code(data.languages.term) in opts["voices"]
            if opts["exercise"] == "listen" and not can_listen:
                raise UserError(f"Listening needs a {data.languages.term} voice in your browser, and there is none. "
                                "See Pronunciation on the start page for how to add one.")
            config.update_prefs(
                mode=opts["mode"], topics=opts["topics"], direction=opts["direction"], exercise=opts["exercise"],
                order=opts["order"], difficult_threshold=opts["threshold"],
                repeat_until_mastered=opts["repeat"], session_size=opts["size"],
            )
            seed = random.randrange(2**31)
            deck = self._build(data, opts, seed=seed, limit=opts["size"])
            if not deck:
                raise UserError("No words match these settings.")
            self._data = data
            self._saved = None
            self._session = ReviewSession(
                deck, direction=opts["direction"], repeat=opts["repeat"], seed=seed,
                scheduler=srs.make_scheduler(fuzz=self._fuzz),
                exercise=opts["exercise"], pool=data.cards, can_listen=can_listen,
            )
            return self.view()

    def _build(self, data: WorkbookData, opts: dict, *, seed: int, limit: int | None) -> list[Card]:
        cards = data.cards
        if opts["exercise"] == "article":
            # Only nouns with a plain der/die/das can be drilled; filtering here (not
            # falling back per card) makes "N words in scope" count what you'll get.
            cards = [c for c in cards if exercises.article_of(c)]
        return build_deck(
            cards, mode=opts["mode"], topics=opts["topics"],
            difficult_threshold=opts["threshold"], order=opts["order"],
            seed=seed, limit=limit, now=self._now(), local_tz=self._tz,
        )

    # ------------------------------------------------------------------ #
    # Reviewing
    # ------------------------------------------------------------------ #

    def view(self) -> dict:
        """The current stage and what it shows."""
        with self._lock:
            if self._session is None:
                return {"stage": "saved", **self._saved} if self._saved else {"stage": "setup"}
            if self._session.finished:
                return {"stage": "summary", **self._summary()}
            return {"stage": "review", **self._card_view()}

    def grade(self, grade) -> dict:
        try:
            grade = int(grade)
        except (TypeError, ValueError) as exc:
            raise UserError("grade must be a number 1-5") from exc
        if not CONFIDENCE_MIN <= grade <= CONFIDENCE_MAX:
            raise UserError("grade must be a number 1-5")
        with self._lock:
            self._active().grade(grade, self._now())
            return self.view()

    def check(self, answer) -> dict:
        """Check the answer to a multiple-choice or fill-in-the-blank card."""
        with self._lock:
            try:
                self._active().check(answer)
            except ValueError as exc:
                raise UserError(str(exc)) from exc
            return self.view()

    def skip(self) -> dict:
        with self._lock:
            self._active().skip()
            return self.view()

    def end(self) -> dict:
        with self._lock:
            self._active().end()
            return self.view()

    def discard(self) -> dict:
        with self._lock:
            self._session = None
            self._data = None
            self._saved = None
            return self.view()

    def write(self) -> dict:
        """Save the session's grades to the workbook (after a backup), then log them."""
        with self._lock:
            session = self._session
            if session is None or not session.finished:
                raise UserError("There is no finished session to save.")
            data = self._data
            updates = session.cell_updates()
            touched = {sheet for (sheet, _row), cols in updates.items() if "M" in cols}
            for sheet in touched & set(data.sheets_missing_srs_headers):
                updates[(sheet, 1)] = dict(srs.SRS_HEADERS)
            try:
                result = apply_updates(data.path, updates, original_mtime=data.mtime,
                                       backup_dir=str(paths.backup_dir()))
            except WritebackError as exc:
                raise UserError(str(exc)) from exc
            except PermissionError as exc:
                # Windows locks a workbook that is open in Excel.
                raise UserError("The workbook is open in another program (Excel?). "
                                "Close it there and press Save again.") from exc
            log_error = None
            try:
                _append_review_log(data, session)
            except OSError as exc:
                # The workbook is saved; a missing log line must not undo that or hide it.
                log_error = f"The review log could not be written ({exc})."
            self._saved = _saved_view(result, len(session.graded_ids), log_error)
            self._session = None
            self._data = None
            return self.view()

    # ------------------------------------------------------------------ #
    # internals
    # ------------------------------------------------------------------ #

    def _load_reference(self) -> WorkbookData:
        ref = config.get_reference_path()
        if not ref:
            raise UserError("Choose your vocabulary workbook first.")
        try:
            return load_cards(ref)
        except WorkbookError as exc:
            raise UserError(str(exc)) from exc

    def _active(self) -> ReviewSession:
        if self._session is None:
            raise UserError("No session in progress.")
        if self._session.finished:
            raise UserError("This session has ended. Save or discard it.")
        return self._session

    def _card_view(self) -> dict:
        s = self._session
        card = s.current_card
        now = self._now()
        ex = s.current_exercise()
        direction = s.direction_for(card.id)
        # Some exercises fix the side you see: the article drill shows the noun,
        # typing the word shows its meaning.
        direction = {"article": "term_to_native", "recall": "native_to_term",
                     "listen": "term_to_native"}.get(ex.kind, direction)
        pair = card.languages
        if direction == "term_to_native":
            front = {"text": card.term, "language": pair.term, "rtl": is_rtl(pair.term),
                     "lang": language_code(pair.term), "hint": card.pronunciation or ""}
        else:
            # No pronunciation on this side: next to the meaning it would spell out the answer.
            front = {"text": card.meaning, "language": pair.native, "rtl": is_rtl(pair.native),
                     "lang": language_code(pair.native), "hint": ""}

        schedule = s.effective_schedule(card.id)
        state = srs.status(schedule, now, self._tz)
        if state == srs.NEW:
            schedule_text = "new word"
        elif state == srs.DUE:
            schedule_text = "due now"
        else:
            schedule_text = f"next review {srs.describe_due(schedule.due, now, self._tz)}"

        grades = []
        for g in range(CONFIDENCE_MIN, CONFIDENCE_MAX + 1):
            conf, sched = s.preview(card.id, g, now)
            grades.append({
                "grade": g,
                "label": GRADE_LABELS[g],
                "confidence": format_confidence(conf),
                "next": "schedule unchanged (repeat)" if sched is None
                        else f"next review {srs.describe_due(sched.due, now, self._tz)}",
            })

        exercise: dict = {"kind": ex.kind}
        if ex.choice:
            native = ex.choice.answer_side == "native"
            lang_name = pair.native if native else pair.term
            exercise.update(options=ex.choice.options, rtl=is_rtl(lang_name), lang=language_code(lang_name))
        if ex.blank:
            # answer_len sizes the input box: a length hint, as on paper worksheets.
            exercise.update(before=ex.blank.before, after=ex.blank.after, rtl=is_rtl(pair.term),
                            answer_len=len(ex.blank.answer),
                            lang=language_code(pair.term), hint=card.meaning,
                            hint_rtl=is_rtl(pair.native), hint_example=card.example_meaning or "")
            if ex.kind == "recall":
                # The meaning is already the front; the word type narrows it down
                # ("to talk" vs "the talk") without giving the word away.
                exercise.update(hint=card.word_type or "", hint_rtl=False, hint_example="")
            if ex.kind == "listen":
                # say: what the page speaks. Word type only as a hint: the meaning
                # would let you write the word without listening at all.
                exercise.update(say=card.term, hint=card.word_type or "", hint_rtl=False, hint_example="")
            if ex.kind in ("blank", "listen") and s.result is None:
                # No written word up front: in a blank the sentence is the prompt, in listening the sound is.
                front = {"text": "", "language": pair.term, "rtl": is_rtl(pair.term),
                         "lang": language_code(pair.term), "hint": ""}

        return {
            "exercise": exercise,
            "result": s.result,
            "progress": {
                "done": len(s.graded_ids),
                "total": len(s.deck),
                "repeats": len([i for i in s.pending if i != card.id]),
            },
            "card": {
                "id": card.id,
                "topic": card.topic,
                "direction_label": pair.front_label(direction),
                "front": front,
                "confidence": format_confidence(s.effective_confidence(card.id)),
                "schedule": schedule_text,
                "back": _back(card),
            },
            "grades": grades,
        }

    def _summary(self) -> dict:
        s = self._session
        now = self._now()
        rows = []
        for cid in s.graded_ids:
            card = s.cards[cid]
            sched = s.schedules.get(cid)
            rows.append({
                "topic": card.topic,
                "term": card.term,
                "meaning": card.meaning,
                "term_rtl": is_rtl(card.languages.term),
                "meaning_rtl": is_rtl(card.languages.native),
                "old": format_confidence(card.confidence) if card.confidence is not None else "—",
                "new": format_confidence(s.confidence[cid]),
                "next": srs.describe_due(sched.due, now, self._tz) if sched else "unchanged",
                "grades": [e.grade for e in s.log if e.card_id == cid],
            })
        rows.sort(key=lambda r: (r["topic"], r["term"].lower()))
        pair = self._data.languages
        return {
            "rows": rows,
            "languages": {"term": pair.term, "native": pair.native},
            "cells": sum(len(c) for c in s.cell_updates().values()),
            "path": self._data.path,
        }


# --------------------------------------------------------------------------- #
# helpers
# --------------------------------------------------------------------------- #

def _deck_options(params: dict) -> dict:
    """Validate the deck settings sent by the page."""
    params = params or {}
    mode = params.get("mode", "due")
    order = params.get("order", "weighted")
    direction = params.get("direction", "mixed")
    exercise = params.get("exercise", "flashcard")
    if exercise not in EXERCISES:
        raise UserError(f"Unknown exercise {exercise!r}.")
    if mode not in MODES:
        raise UserError(f"Unknown mode {mode!r}.")
    if order not in ORDERS:
        raise UserError(f"Unknown order {order!r}.")
    if direction not in DIRECTIONS:
        raise UserError(f"Unknown direction {direction!r}.")
    voices = params.get("voices") or []
    if not isinstance(voices, list) or not all(isinstance(v, str) for v in voices):
        raise UserError("voices must be a list of language codes.")
    topics = params.get("topics") or []
    if not isinstance(topics, list) or not all(isinstance(t, str) for t in topics):
        raise UserError("topics must be a list of sheet names.")
    if mode == "topic" and not topics:
        raise UserError("Pick at least one topic.")
    try:
        threshold = int(params.get("threshold", 2))
        size = int(params.get("size", 20))
    except (TypeError, ValueError) as exc:
        raise UserError("threshold and size must be whole numbers.") from exc
    if not CONFIDENCE_MIN <= threshold <= CONFIDENCE_MAX:
        raise UserError("The difficulty threshold must be between 1 and 5.")
    if not 1 <= size <= MAX_SESSION_SIZE:
        raise UserError(f"Words per session must be between 1 and {MAX_SESSION_SIZE}.")
    return {"mode": mode, "order": order, "direction": direction, "exercise": exercise, "topics": topics,
            "voices": [v.lower() for v in voices],
            "threshold": threshold, "size": size, "repeat": bool(params.get("repeat", False))}


def _back(card: Card) -> dict:
    pair = card.languages
    return {
        "term": card.term,
        "meaning": card.meaning,
        "term_language": pair.term,
        "native_language": pair.native,
        "term_rtl": is_rtl(pair.term),
        "native_rtl": is_rtl(pair.native),
        "term_lang": language_code(pair.term),
        "native_lang": language_code(pair.native),
        "article_label": card.article_label,
        "article": card.article_aux,
        "pronunciation": card.pronunciation,
        "word_type": card.word_type,
        "forms_label": card.forms_label,
        "forms": card.forms,
        "example_term": card.example_term,
        "example_meaning": card.example_meaning,
        "synonyms": card.synonyms,
        "notes": card.notes,
    }


def _saved_view(result: WriteResult, words: int, log_error: str | None) -> dict:
    return {
        "words": words,
        "cells": result.cells_written,
        "sheets": result.sheets_touched,
        "backup": result.backup_path,
        "path": result.path,
        "warning": log_error,
    }


REVIEW_LOG_FIELDS = ("reviewed_at", "workbook", "sheet", "row", "term", "grade", "fsrs_rating",
                     "scheduled", "confidence_before", "confidence_after", "due_after")


def _append_review_log(data: WorkbookData, session: ReviewSession) -> None:
    """One CSV line per grade, in the data folder.

    Not read by the app today. Kept because FSRS can fit its parameters to
    *your* review history (fsrs Optimizer), which needs every review with its
    time and rating -- information the workbook's current-state columns lose.
    """
    path = paths.reviews_path()
    path.parent.mkdir(parents=True, exist_ok=True)
    new_file = not path.exists()
    with open(path, "a", newline="", encoding="utf-8") as fh:
        writer = csv.writer(fh)
        if new_file:
            writer.writerow(REVIEW_LOG_FIELDS)
        for e in session.log:
            card = session.cards[e.card_id]
            writer.writerow([
                srs.format_time(e.reviewed_at), os.path.basename(data.path), card.sheet, card.row,
                card.term, e.grade, srs.GRADE_TO_RATING[e.grade].name, int(e.scheduled),
                "" if e.confidence_before is None else e.confidence_before, e.confidence_after,
                "" if e.due_after is None else srs.format_time(e.due_after),
            ])


def browse_for_file() -> str | None:
    """Open the system's file picker and return the chosen path (or None).

    Tk runs in a throwaway process on purpose: a Tk main loop inside the web
    server's thread can dead-lock it, and a subprocess sidesteps that entirely.
    """
    code = (
        "import tkinter as tk\n"
        "from tkinter import filedialog\n"
        "r = tk.Tk(); r.withdraw(); r.attributes('-topmost', True)\n"
        "print(filedialog.askopenfilename("
        "title='Select the vocabulary workbook', "
        "filetypes=[('Excel workbook', '*.xlsx *.xlsm'), ('All files', '*.*')]))\n"
    )
    # pythonw.exe (the GUI launcher) has no console; python.exe next to it
    # works the same for a Tk dialog and is what a plain venv always has.
    exe = sys.executable
    if exe.lower().endswith("pythonw.exe"):
        exe = exe[: -len("pythonw.exe")] + "python.exe"
    try:
        done = subprocess.run([exe, "-c", code], capture_output=True, text=True, timeout=300,
                              creationflags=getattr(subprocess, "CREATE_NO_WINDOW", 0))
    except (OSError, subprocess.TimeoutExpired):
        return None
    return done.stdout.strip() or None
