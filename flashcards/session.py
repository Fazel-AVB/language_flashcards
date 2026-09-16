"""Deck building: turn the full card list into an ordered review queue.

Pure functions only -- no Streamlit, no I/O -- so the review logic is testable in
isolation. The app layer owns session state (which card is showing, what's been
graded); this module just decides *which cards* and *in what order*.
"""

from __future__ import annotations

import random
from datetime import date

from .models import Card

MODES = ("topic", "mix", "difficult")
ORDERS = ("lowest_confidence_first", "random", "oldest_first")


def build_deck(
    cards: list[Card],
    *,
    mode: str,
    topics: list[str] | None = None,
    difficult_threshold: int = 2,
    order: str = "lowest_confidence_first",
    seed: int | None = None,
) -> list[Card]:
    """Filter and order the cards for one review session.

    mode:
      "topic"     -> only the sheets in `topics`
      "mix"       -> every topic
      "difficult" -> only cards with confidence <= difficult_threshold
                     (ungraded cards are excluded -- there is no score to be
                     "difficult" yet); still honours `topics` if given
    """
    if mode not in MODES:
        raise ValueError(f"unknown mode: {mode!r}")
    if order not in ORDERS:
        raise ValueError(f"unknown order: {order!r}")

    selected = list(cards)

    if topics and mode in ("topic", "difficult"):
        wanted = set(topics)
        selected = [c for c in selected if c.topic in wanted]

    if mode == "difficult":
        selected = [
            c for c in selected
            if c.confidence is not None and c.confidence <= difficult_threshold
        ]

    return _order(selected, order, seed)


def _order(cards: list[Card], order: str, seed: int | None) -> list[Card]:
    rng = random.Random(seed)
    if order == "random":
        shuffled = list(cards)
        rng.shuffle(shuffled)
        return shuffled

    if order == "oldest_first":
        # Undated cards sort last; ties keep workbook order (stable sort).
        return sorted(cards, key=lambda c: (c.date_added is None, c.date_added or date.max))

    # lowest_confidence_first: ungraded (None) treated as most in need -> sort first.
    # Small random tiebreak so equal-confidence cards aren't always in the same order.
    jitter = {c.id: rng.random() for c in cards}
    return sorted(
        cards,
        key=lambda c: (c.confidence if c.confidence is not None else -1, jitter[c.id]),
    )


def pick_direction(pref: str, card_id: str, seed: int | None = None) -> str:
    """Resolve the front/back direction for one card.

    "mixed" -> deterministic per (seed, card) so a card keeps its direction until
    it is graded and re-drawn, but different cards vary.
    """
    if pref in ("de_en", "en_de"):
        return pref
    # String seed: a tuple seed is deprecated (hashing-based) since Python 3.9.
    rng = random.Random(f"{seed}:{card_id}")
    return "de_en" if rng.random() < 0.5 else "en_de"
