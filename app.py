"""Flashcards -- Streamlit UI.

Run locally:  streamlit run app.py     (or double-click launch.bat on Windows)

This module is UI + session state only. All data logic lives in the `flashcards`
package so it can be tested without Streamlit. Nothing is written to the
workbook until the user confirms on the summary screen.

Language-pair agnostic: every visible language name comes from the workbook's own
A1/B1 headers via `models.LanguagePair`, so the same app serves German/English and
English/Persian. Do not reintroduce hard-coded "German" / "English" labels.
"""

from __future__ import annotations

import html
import subprocess
import sys

import streamlit as st

from flashcards import config
from flashcards.models import (
    CONFIDENCE_MAX,
    Card,
    blend_confidence,
    format_confidence,
    is_rtl,
)
from flashcards.session import DIRECTIONS, ORDERS, build_deck, pick_direction, pick_next_repeat
from flashcards.workbook import WorkbookError, load_cards
from flashcards.writeback import WritebackError, apply_confidence_updates

st.set_page_config(page_title="Flashcards", page_icon="\U0001F5C2", layout="centered")

ORDER_LABELS = {
    "weighted": "Weighted (weakest likeliest)",
    "lowest_confidence_first": "Lowest confidence first",
    "random": "Random",
    "oldest_first": "Oldest first",
}

GRADE_LABELS = {
    1: "1 - needs work",
    2: "2 - shaky",
    3: "3 - getting there",
    4: "4 - solid",
    5: "5 - mastered",
}

# Inline styles for card text. Streamlit's own markdown cannot carry a writing
# direction, so card text goes through `_md` as HTML (see there); these keep the
# result looking like the h1/blockquote it replaces.
_FRONT_STYLE = "font-size:2.6rem;font-weight:700;line-height:1.25;margin:.3rem 0"
_TERM_STYLE = "font-size:1.6rem;font-weight:600;margin:.2rem 0"
_QUOTE_LTR = "border-left:3px solid rgba(128,128,128,.4);padding-left:.7rem;margin:.3rem 0"
_QUOTE_RTL = "border-right:3px solid rgba(128,128,128,.4);padding-right:.7rem;margin:.3rem 0"


# --------------------------------------------------------------------------- #
# Bidirectional text
# --------------------------------------------------------------------------- #

def _md(text: str, language: str, *, style: str = "", italic: bool = False,
        quote: bool = False) -> None:
    """Render one piece of card text in the correct writing direction.

    Streamlit markdown has no direction control, so right-to-left text (Persian,
    Arabic, Hebrew) needs an explicit dir attribute: without it trailing
    punctuation jumps to the wrong end and embedded Latin words reorder. HTML is
    used for card text only; every other label stays plain markdown.

    `text` is escaped because vocabulary legitimately contains < and & (e.g. a
    note like "a < b"), which would otherwise be swallowed as markup.
    """
    rtl = is_rtl(language)
    if quote:
        style = f"{_QUOTE_RTL if rtl else _QUOTE_LTR};{style}"
    if italic:
        style = f"{style};font-style:italic"
    # Built by concatenation, not an f-string: Python 3.10 rejects a backslash
    # inside an f-string expression, and the dir attribute needs quoted "rtl".
    direction = ' dir="rtl"' if rtl else ""
    st.markdown(
        '<div' + direction + ' style="' + style + '">' + html.escape(text) + "</div>",
        unsafe_allow_html=True,
    )


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
                f"{data.languages.term} \u2192 {data.languages.native}  \u00b7  "
                f"{len(data.cards)} words  \u00b7  {len(data.topics)} topics"
            )
            # A sheet whose A1/B1 disagree reviews fine (each card carries its own
            # pair) but cannot be summarised by one label, so say so rather than
            # quietly mislabel the deck.
            if data.language_conflicts:
                st.sidebar.warning(
                    "These sheets declare a different language pair in A1/B1: "
                    + ", ".join(data.language_conflicts)
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

_DECK_KEYS = ("first_pass", "pending", "draw", "current", "revealed", "changes",
              "graded_ids", "deck_ids", "seed", "repeat", "direction")


def _reset_session() -> None:
    for key in ("wb", "stage", "write_done", *_DECK_KEYS):
        st.session_state.pop(key, None)


def _reset_session_keep_workbook() -> None:
    """Clear the finished deck but keep the loaded workbook; stay on the summary."""
    for key in _DECK_KEYS:
        st.session_state.pop(key, None)
    st.session_state["stage"] = "summary"


def _ensure_workbook(path: str) -> str | None:
    """Load the workbook into session state if needed. Returns an error message.

    Returns the message instead of rendering it: `main` draws the page title from
    the loaded workbook's language pair, so it must decide layout order, not this.
    """
    if st.session_state.get("wb") and st.session_state["wb"].path == path:
        return None
    try:
        st.session_state["wb"] = load_cards(path)
    except WorkbookError as exc:
        return str(exc)
    st.session_state["stage"] = "setup"
    return None


def _cards_by_id() -> dict[str, Card]:
    return {c.id: c for c in st.session_state["wb"].cards}


def _effective_confidence(card: Card) -> float | None:
    """The card's score as of right now: the pending session value if it has
    already been graded this session, else what the workbook holds.

    Grades are averaged (models.blend_confidence), so a re-drill must compound on
    the session's own result -- averaging against the on-disk value every time
    would make repeated drilling of one word a no-op.
    """
    return st.session_state["changes"].get(card.id, card.confidence)


# --------------------------------------------------------------------------- #
# Stages
# --------------------------------------------------------------------------- #

def _stage_setup(prefs: dict) -> None:
    wb = st.session_state["wb"]
    pair = wb.languages
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
            # Fall back to the first entry rather than raising: a config.json
            # written by an older version can name an order that no longer exists.
            index=ORDERS.index(prefs["order"]) if prefs["order"] in ORDERS else 0,
            format_func=lambda o: ORDER_LABELS.get(o, o.replace("_", " ")),
        )
    with col2:
        # Labels come from the workbook's pair, so this widget reads
        # "German -> English" or "English -> Persian" with no code change.
        direction_labels = {
            "mixed": "Mixed (random)",
            "term_to_native": f"{pair.term} \u2192 {pair.native}",
            "native_to_term": f"{pair.native} \u2192 {pair.term}",
        }
        direction = st.selectbox(
            "Direction",
            DIRECTIONS,
            index=DIRECTIONS.index(prefs["direction"]) if prefs["direction"] in DIRECTIONS else 0,
            format_func=direction_labels.get,
        )
    size = int(st.number_input(
        "Words per session (unique)",
        min_value=1, max_value=500, step=5,
        value=int(prefs["session_size"]),
        help="The deck is drawn from everything in scope. With the weighted order, "
             "a word at 1 is 81x likelier to be drawn than one at 5.",
    ))
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

    # Unlimited: this is the pool the session draws from, not the deck itself.
    in_scope = build_deck(
        wb.cards, mode=mode, topics=topics, difficult_threshold=threshold, order=order, seed=0
    )
    drawn = min(size, len(in_scope))
    note = "" if len(in_scope) <= size else f"  Drawing {drawn} of them."
    st.caption(f"{len(in_scope)} card(s) in scope.{note}")

    if st.button("Start review", type="primary", disabled=not in_scope):
        config.update_prefs(
            direction=direction, order=order, difficult_threshold=threshold,
            repeat_until_mastered=repeat, session_size=size,
        )
        seed = st.session_state.get("_run_seed", 0) + 1
        st.session_state["_run_seed"] = seed
        deck = build_deck(
            wb.cards, mode=mode, topics=topics, difficult_threshold=threshold,
            order=order, seed=seed, limit=size,
        )
        st.session_state.update(
            stage="review",
            seed=seed,
            deck_ids=[c.id for c in deck],
            # first_pass: every drawn word is shown exactly once, in deck order.
            # pending: words still below 5, re-drilled afterwards by live weight.
            first_pass=[c.id for c in deck],
            pending=[],
            draw=0,
            current=deck[0].id,
            revealed=False,
            changes={},
            graded_ids=set(),
            repeat=repeat,
            direction=direction,
        )
        st.rerun()


def _front_text(card: Card, direction: str) -> tuple[str, str, str]:
    """(front text, its language, hint).

    The pronunciation hint is shown only on the term side: next to the meaning it
    would spell out the answer.
    """
    if direction == "term_to_native":
        return card.term, card.languages.term, card.pronunciation or ""
    return card.meaning, card.languages.native, ""


def _render_back(card: Card) -> None:
    pair = card.languages
    if card.article_aux:
        st.markdown(f"**{card.article_label}:** {card.article_aux}")
    _md(card.term, pair.term, style=_TERM_STYLE)
    if card.pronunciation:
        st.markdown(f"*{card.pronunciation}*")
    st.markdown(f"**{pair.native}:**")
    _md(card.meaning, pair.native)
    meta = [x for x in (card.word_type, card.forms and f"{card.forms_label}: {card.forms}") if x]
    if meta:
        st.caption("  \u00b7  ".join(meta))
    if card.example_term:
        _md(card.example_term, pair.term, quote=True)
        if card.example_meaning:
            _md(card.example_meaning, pair.native, quote=True, italic=True)
    if card.synonyms:
        st.markdown("**Related:**")
        _md(card.synonyms, pair.term)
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
    pending_repeats = len([i for i in st.session_state["pending"] if i != cid])
    tag = card.languages.front_label(direction)
    st.caption(f"{card.topic}  \u00b7  {tag}" + (f"  \u00b7  {pending_repeats} repeat(s) queued" if pending_repeats > 0 else ""))

    front, front_language, hint = _front_text(card, direction)
    _md(front, front_language, style=_FRONT_STYLE)
    if hint:
        st.markdown(f"*{hint}*")
    old = _effective_confidence(card)
    st.caption(f"current confidence: {format_confidence(old)}")

    st.divider()

    if not st.session_state["revealed"]:
        if st.button("Show answer", type="primary", use_container_width=True):
            st.session_state["revealed"] = True
            st.rerun()
    else:
        _render_back(card)
        st.divider()
        st.write("Set the new confidence:")
        st.caption(
            f"Your grade is averaged with the current score ({format_confidence(old)}), "
            "not substituted for it."
        )
        cols = st.columns(5)
        for grade, col in zip(range(1, 6), cols):
            # Show the averaged outcome up front: with blending, pressing "5" on a
            # weak word does not set 5, which is otherwise a nasty surprise.
            preview = format_confidence(blend_confidence(old, grade))
            if col.button(GRADE_LABELS[grade], key=f"grade_{grade}",
                          help=f"new score: {preview}", use_container_width=True):
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
    new = blend_confidence(_effective_confidence(card), grade)
    # Record only real changes so the summary and write-back stay minimal.
    # Compare against the on-disk value: an average that lands back on it is not
    # a change, even though the card was graded.
    if new == card.confidence:
        st.session_state["changes"].pop(cid, None)
    else:
        st.session_state["changes"][cid] = new
    st.session_state["graded_ids"].add(cid)
    # Mastery is the *stored* score, not the button pressed: after averaging, one
    # press of 5 on a weak word lands well below 5 and it should come round again.
    _advance(repeat_current=st.session_state["repeat"] and new < CONFIDENCE_MAX)


def _advance(*, repeat_current: bool) -> None:
    """Move to the next card: finish the first pass, then re-drill by live weight.

    Two phases on purpose. Every drawn word is shown once (phase 1) before any
    repeat, so capping the session at N words still means you *see* all N; only
    then do repeats start, and those are re-weighted on each pass.
    """
    cid = st.session_state["current"]
    first_pass: list[str] = st.session_state["first_pass"]
    pending: list[str] = st.session_state["pending"]

    if first_pass and first_pass[0] == cid:
        first_pass.pop(0)
    if repeat_current:
        if cid not in pending:
            pending.append(cid)
    elif cid in pending:
        pending.remove(cid)  # reached 5 (or was skipped): done for this session

    st.session_state["revealed"] = False

    if first_pass:
        st.session_state["current"] = first_pass[0]
        return

    if pending:
        # Re-weight on the session's current scores, not the on-disk ones: a word
        # that just improved yields its turn to the words that did not.
        draw = st.session_state["draw"] + 1
        st.session_state["draw"] = draw
        cards = _cards_by_id()
        scores = {i: _effective_confidence(cards[i]) for i in pending}
        nxt = pick_next_repeat(scores, exclude=cid, seed=st.session_state["seed"], draw=draw)
        if nxt is not None:
            st.session_state["current"] = nxt
            return

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
    pair = st.session_state["wb"].languages
    changes: dict[str, float] = st.session_state["changes"]

    if not changes:
        st.info("No confidence changes to save.")
    else:
        rows = []
        for cid, new in changes.items():
            c = cards[cid]
            # Column headers are the language names, so the table reads correctly
            # whichever pair the workbook holds.
            rows.append({
                "Topic": c.topic,
                pair.term: c.term,
                pair.native: c.meaning,
                "Old": format_confidence(c.confidence) if c.confidence is not None else "\u2014",
                "New": format_confidence(new),
            })
        rows.sort(key=lambda r: (r["Topic"], r[pair.term]))
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
    # Sidebar first, then the title: the title names the target language, which is
    # only known once the workbook is loaded. Sidebar and main area render in
    # separate columns, so call order does not affect the visible layout.
    path = _reference_sidebar()

    if not path:
        st.title("Flashcards")
        st.info("Set a reference file in the sidebar to begin.")
        return

    error = _ensure_workbook(path)
    wb = st.session_state.get("wb")
    st.title(f"{wb.languages.term} Flashcards" if wb else "Flashcards")
    if error:
        st.error(error)
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
