"""Read the reference workbook into a list of Cards (read-only).

Only *topic* sheets are read (see `models.NON_TOPIC_SHEETS` and
`models.is_topic_header`). The dashboard sheets are formula-driven and are never
parsed or written by this app.

Downstream: `WorkbookData.mtime` is captured here and re-checked by
`writeback.apply_confidence_updates` -- if the file changed on disk between load
and save, the write is refused so a hand edit in Excel is never clobbered.
"""

from __future__ import annotations

import os
from dataclasses import dataclass, field
from datetime import datetime

from openpyxl import load_workbook

from .models import (
    CONFIDENCE_COL_INDEX,
    NON_TOPIC_SHEETS,
    Card,
    is_topic_header,
    parse_row,
)


class WorkbookError(RuntimeError):
    """The file could not be read as a valid reference workbook."""


@dataclass
class TopicStat:
    topic: str
    count: int
    graded: int
    avg_confidence: float | None
    difficult: int  # cards at or below the difficult threshold


@dataclass
class WorkbookData:
    path: str
    mtime: float
    cards: list[Card]
    topics: list[str] = field(default_factory=list)

    def stats(self, difficult_threshold: int) -> list[TopicStat]:
        out: list[TopicStat] = []
        for topic in self.topics:
            group = [c for c in self.cards if c.topic == topic]
            graded = [c.confidence for c in group if c.confidence is not None]
            out.append(
                TopicStat(
                    topic=topic,
                    count=len(group),
                    graded=len(graded),
                    avg_confidence=(sum(graded) / len(graded)) if graded else None,
                    difficult=sum(
                        1 for c in group
                        if c.confidence is not None and c.confidence <= difficult_threshold
                    ),
                )
            )
        return out


def load_cards(path: str) -> WorkbookData:
    """Parse every topic sheet into Cards.

    Raises WorkbookError with a user-facing message on anything unreadable so the
    UI can show it verbatim.
    """
    if not os.path.isfile(path):
        raise WorkbookError(f"File not found: {path}")

    # Capture mtime BEFORE reading so a concurrent write is caught by the guard,
    # not masked by our own read time.
    mtime = os.path.getmtime(path)

    try:
        # data_only: topic sheets hold no formulas, but this also returns cached
        # values for any that appear later instead of the formula string.
        wb = load_workbook(path, read_only=True, data_only=True)
    except Exception as exc:  # openpyxl raises a zoo of exception types
        raise WorkbookError(f"Could not open workbook: {exc}") from exc

    cards: list[Card] = []
    topics: list[str] = []
    try:
        for name in wb.sheetnames:
            if name in NON_TOPIC_SHEETS:
                continue
            ws = wb[name]
            rows = ws.iter_rows(values_only=True)
            try:
                header = list(next(rows))
            except StopIteration:
                continue
            if not is_topic_header(header):
                continue

            topics.append(name)
            header = header[:CONFIDENCE_COL_INDEX]
            # openpyxl is 1-based; row 1 is the header, data starts at row 2.
            for offset, values in enumerate(rows, start=2):
                card = parse_row(name, header, list(values)[:CONFIDENCE_COL_INDEX], offset)
                if card is not None:
                    cards.append(card)
    finally:
        wb.close()

    if not topics:
        raise WorkbookError(
            "No topic sheets found. A topic sheet needs 'German' in column A and "
            "'Confidence (1-5)' in column L."
        )

    return WorkbookData(path=path, mtime=mtime, cards=cards, topics=topics)


def describe_reference(path: str) -> str:
    """One-line summary for the sidebar."""
    try:
        data = load_cards(path)
    except WorkbookError as exc:
        return f"unreadable ({exc})"
    when = datetime.fromtimestamp(data.mtime).strftime("%Y-%m-%d %H:%M")
    return f"{len(data.cards)} words / {len(data.topics)} topics / modified {when}"
