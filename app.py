"""German flashcards -- Streamlit UI.

Run locally:  streamlit run app.py     (or double-click launch.bat on Windows)

This module is UI + session state only. All data logic lives in the `flashcards`
package so it can be tested without Streamlit. Nothing is written to the
workbook until the user confirms on the summary screen.
"""

from __future__ import annotations

import subprocess
import sys

import streamlit as st

from flashcards import config
from flashcards.models import Card
from flashcards.session import ORDERS, build_deck, pick_direction
from flashcards.workbook import WorkbookError, load_cards
from flashcards.writeback import WritebackError, apply_confidence_updates

st.set_page_config(page_title="German Flashcards", page_icon="\U0001F1E9\U0001F1EA", layout="centered")

GRADE_LABELS = {
    1: "1 - needs work",
    2: "2 - shaky",
    3: "3 - getting there",
    4: "4 - solid",
    5: "5 - mastered",
}


# --------------------------------------------------------------------------- #
# Reference file
# --------------------------------------------------------------------------- #

def _browse_for_file() -> str | None:
    """Open a native file picker in a throwaway process.

    Tk is run out-of-process on purpose: a Tk mainloop inside the Streamlit
    server thread can dead-lock the app. A subprocess sidesteps that entirely.
    """
    code = (
        "import tkinter as tk\n"
        "from tkinter import filedialog\n"
        "r = tk.Tk(); r.withdraw(); r.attributes('-topmost', True)\n"
        "print(filedialog.askopenfilename("
        "title='Select the vocabulary workbook', "
        "filetypes=[('Excel workbook', '*.xlsx *.xlsm'), ('All files', '*.*')]))\n"
    )
    try:
        done = subprocess.run(
            [sys.executable, "-c", code], capture_output=True, text=True, timeout=180
        )
        return done.stdout.strip() or None
    except Exception:
        return None


def _reference_sidebar() -> str | None:
    st.sidebar.header("Reference file")
    current = config.get_reference_path()

    if current:
        st.sidebar.success(current, icon="\U0001F4D8")
        try:
            data = load_cards(current)
            st.sidebar.caption(
                f"{len(data.cards)} words - {len(data.topics)} topics"
            )
        except WorkbookError as exc:
            st.sidebar.error(str(exc))
    else:
        st.sidebar.info("No reference file set yet. Choose one below - it will be remembered.")

    with st.sidebar.expander("Change reference file", expanded=not current):
        if st.button("Browse\u2026", use_container_width=True):
            picked = _browse_for_file()
            if picked:
                st.session_state["_pending_path"] = picked
            else:
                st.warning("No file chosen (or the picker is unavailable - paste a path instead).")

        pending = st.session_state.get("_pending_path", "")
        typed = st.text_input(
            "\u2026or paste the full path",
            value=pending,
            placeholder=r"C:\University\Language\German_learning.xlsx",
        )
        if st.button("Use this file", type="primary", use_container_width=True):
            try:
                config.set_reference_path(typed or pending)
            except ValueError as exc:
                st.error(str(exc))
            else:
                st.session_state.pop("_pending_path", None)
                _reset_session()
                st.rerun()

    return config.get_reference_path()


# --------------------------------------------------------------------------- #
# Session state
# --------------------------------------------------------------------------- #

_DECK_KEYS = ("queue", "current", "revealed", "changes", "graded_ids",
              "deck_ids", "seed", "repeat", "direction")


def _reset_session() -> None:
    for key in ("wb", "stage", "write_done", *_DECK_KEYS):
        st.session_state.pop(key, None)


def _reset_session_keep_workbook() -> None:
    """Clear the finished deck but keep the loaded workbook; stay on the summary."""
    for key in _DECK_KEYS:
        st.session_state.pop(key, None)
    st.session_state["stage"] = "summary"


def _ensure_workbook(path: str) -> bool:
    """Load the workbook into session state if not already loaded. Returns success."""
    if st.session_state.get("wb") and st.session_state["wb"].path == path:
        return True
    try:
        st.session_state["wb"] = load_cards(path)
    except WorkbookError as exc:
        st.error(str(exc))
        return False
    st.session_state["stage"] = "setup"
    return True


def _cards_by_id() -> dict[str, Card]:
    return {c.id: c for c in st.session_state["wb"].cards}


# --------------------------------------------------------------------------- #
# Stages
# --------------------------------------------------------------------------- #

def _stage_setup(prefs: dict) -> None:
    wb = st.session_state["wb"]
    st.subheader("Build a deck")

    mode_label = st.radio(
        "Mode",
        ["Topic-wise", "Mix (all topics)", "Difficult words only"],
        horizontal=True,
    )
    mode = {"Topic-wise": "topic", "Mix (all topics)": "mix", "Difficult words only": "difficult"}[mode_label]

    threshold = prefs["difficult_threshold"]
    topics: list[str] = []
    if mode in ("topic", "difficult"):
        topics = st.multiselect("Topics", wb.topics, default=wb.topics if mode == "difficult" else [])
    if mode == "difficult":
        threshold = st.slider("\u201cDifficult\u201d means confidence \u2264", 1, 5, threshold)

    col1, col2 = st.columns(2)
    with col1:
        order = st.selectbox(
            "Order",
            ORDERS,
            index=ORDERS.index(prefs["order"]),
            format_func=lambda o: o.replace("_", " "),
        )
    with col2:
        direction = st.selectbox(
            "Direction",
            ["mixed", "de_en", "en_de"],
            index=["mixed", "de_en", "en_de"].index(prefs["direction"]),
            format_func={"mixed": "Mixed (random)", "de_en": "German \u2192 English",
                         "en_de": "English \u2192 German"}.get,
        )
    repeat = st.checkbox(
        "Keep repeating cards graded below 5 until the deck is clear",
        value=prefs["repeat_until_mastered"],
    )

    with st.expander("Topic overview"):
        rows = [
            {
                "Topic": s.topic,
                "Words": s.count,
                "Graded": s.graded,
                "Avg conf.": None if s.avg_confidence is None else round(s.avg_confidence, 2),
                f"\u2264 {threshold}": s.difficult,
            }
            for s in wb.stats(threshold)
        ]
        st.dataframe(rows, hide_index=True, use_container_width=True)

    preview = build_deck(
        wb.cards, mode=mode, topics=topics, difficult_threshold=threshold, order=order, seed=0
    )
    st.caption(f"{len(preview)} card(s) in scope.")

    if st.button("Start review", type="primary", disabled=not preview):
        config.update_prefs(
            direction=direction, order=order, difficult_threshold=threshold,
            repeat_until_mastered=repeat,
        )
        seed = st.session_state.get("_run_seed", 0) + 1
        st.session_state["_run_seed"] = seed
        deck = build_deck(
            wb.cards, mode=mode, topics=topics, difficult_threshold=threshold,
            order=order, seed=seed,
        )
        st.session_state.update(
            stage="review",
            seed=seed,
            deck_ids=[c.id for c in deck],
            queue=[c.id for c in deck],
            current=deck[0].id,
            revealed=False,
            changes={},
            graded_ids=set(),
            repeat=repeat,
            direction=direction,
        )
        st.rerun()


def _front_text(card: Card, direction: str) -> tuple[str, str]:
    if direction == "de_en":
        hint = card.pronunciation or ""
        return card.german, hint
    return card.english, ""


def _render_back(card: Card) -> None:
    if card.article_aux:
        st.markdown(f"**{card.article_label}:** {card.article_aux}")
    st.markdown(f"### {card.german}")
    if card.pronunciation:
        st.markdown(f"*{card.pronunciation}*")
    st.markdown(f"**English:** {card.english}")
    meta = [x for x in (card.word_type, card.forms and f"{card.forms_label}: {card.forms}") if x]
    if meta:
        st.caption("  \u00b7  ".join(meta))
    if card.example_de:
        st.markdown(f"> {card.example_de}")
        if card.example_en:
            st.markdown(f"> _{card.example_en}_")
    if card.synonyms:
        st.markdown(f"**Related:** {card.synonyms}")
    if card.notes:
        with st.expander("Notes"):
            st.write(card.notes)


def _stage_review() -> None:
    cards = _cards_by_id()
    cid = st.session_state["current"]
    card = cards[cid]
    direction = pick_direction(st.session_state["direction"], cid, st.session_state["seed"])

    deck_n = len(st.session_state["deck_ids"])
    done_n = len(st.session_state["graded_ids"])
    st.progress(done_n / deck_n if deck_n else 0.0,
               text=f"{done_n} / {deck_n} words graded")
    pending_repeats = len(st.session_state["queue"]) - 1
    tag = "DE \u2192 EN" if direction == "de_en" else "EN \u2192 DE"
    st.caption(f"{card.topic}  \u00b7  {tag}" + (f"  \u00b7  {pending_repeats} repeat(s) queued" if pending_repeats > 0 else ""))

    front, hint = _front_text(card, direction)
    st.markdown(f"# {front}")
    if hint:
        st.markdown(f"*{hint}*")
    old = card.confidence
    st.caption(f"current confidence: {old if old is not None else 'ungraded'}")

    st.divider()

    if not st.session_state["revealed"]:
        if st.button("Show answer", type="primary", use_container_width=True):
            st.session_state["revealed"] = True
            st.rerun()
    else:
        _render_back(card)
        st.divider()
        st.write("Set the new confidence:")
        cols = st.columns(5)
        for grade, col in zip(range(1, 6), cols):
            if col.button(GRADE_LABELS[grade], key=f"grade_{grade}", use_container_width=True):
                _grade_current(grade)
                st.rerun()

    st.divider()
    left, right = st.columns(2)
    if left.button("Skip (no change)"):
        _advance(repeat_current=False)
        st.rerun()
    if right.button("End session"):
        st.session_state["stage"] = "summary"
        st.rerun()


def _grade_current(grade: int) -> None:
    cid = st.session_state["current"]
    card = _cards_by_id()[cid]
    # Record only real changes so the summary and write-back stay minimal.
    if grade == card.confidence:
        st.session_state["changes"].pop(cid, None)
    else:
        st.session_state["changes"][cid] = grade
    st.session_state["graded_ids"].add(cid)
    _advance(repeat_current=st.session_state["repeat"] and grade < 5)


def _advance(*, repeat_current: bool) -> None:
    queue: list[str] = st.session_state["queue"]
    finished = queue.pop(0)
    if repeat_current:
        queue.append(finished)  # re-drill later in the same session
    st.session_state["revealed"] = False
    if queue:
        st.session_state["current"] = queue[0]
    else:
        st.session_state["stage"] = "summary"


def _stage_summary(path: str) -> None:
    st.subheader("Session summary")

    done = st.session_state.get("write_done")
    if done is not None:
        st.success(
            f"Wrote {done.cells_written} confidence value(s) to {', '.join(done.sheets_touched)}."
        )
        st.caption(f"Backup: {done.backup_path}")
        if st.button("New session", type="primary"):
            st.session_state.pop("write_done", None)
            _reset_session()
            st.rerun()
        return

    cards = _cards_by_id()
    changes: dict[str, int] = st.session_state["changes"]

    if not changes:
        st.info("No confidence changes to save.")
    else:
        rows = []
        for cid, new in changes.items():
            c = cards[cid]
            rows.append({
                "Topic": c.topic,
                "German": c.german,
                "English": c.english,
                "Old": c.confidence if c.confidence is not None else "\u2014",
                "New": new,
            })
        rows.sort(key=lambda r: (r["Topic"], r["German"]))
        st.dataframe(rows, hide_index=True, use_container_width=True)
        st.caption(f"{len(rows)} cell(s) will change in {path}. A timestamped backup is made first.")

        c1, c2 = st.columns(2)
        if c1.button("Confirm & write to Excel", type="primary"):
            _write(path)
        if c2.button("Discard changes"):
            _reset_session()
            st.rerun()

    if st.button("New session"):
        _reset_session()
        st.rerun()


def _write(path: str) -> None:
    wb = st.session_state["wb"]
    cards = _cards_by_id()
    updates = {(cards[cid].sheet, cards[cid].row): new
               for cid, new in st.session_state["changes"].items()}
    try:
        result = apply_confidence_updates(
            path, updates, original_mtime=wb.mtime, backup_dir=str(config.BACKUP_DIR)
        )
    except WritebackError as exc:
        st.error(str(exc))
        return
    # Stash the result; the summary stage renders it and offers a fresh start.
    # Keep the loaded workbook so a re-review this session sees the new values
    # only after an explicit reload.
    st.session_state["write_done"] = result
    _reset_session_keep_workbook()
    st.rerun()


# --------------------------------------------------------------------------- #
# Main
# --------------------------------------------------------------------------- #

def main() -> None:
    st.title("German Flashcards")
    path = _reference_sidebar()

    if not path:
        st.info("Set a reference file in the sidebar to begin.")
        return

    if not _ensure_workbook(path):
        return

    prefs = config.load_config()["prefs"]
    st.session_state.setdefault("stage", "setup")

    if st.sidebar.button("Reload workbook from disk"):
        _reset_session()
        st.rerun()

    stage = st.session_state["stage"]
    if stage == "setup":
        _stage_setup(prefs)
    elif stage == "review":
        _stage_review()
    elif stage == "summary":
        _stage_summary(path)


if __name__ == "__main__":
    main()
