"""Deck building and the review session: which cards, in what order, and what
each grade does.

No web framework and no file I/O here, so the whole review loop is testable in
isolation: `build_deck` decides which cards and in what order, `ReviewSession`
runs one session (first pass, repeats, grading) and produces the cell updates
that writeback.apply_updates saves.
"""

from __future__ import annotations

import random
from dataclasses import dataclass, field
from datetime import date, datetime, tzinfo

import fsrs

from . import exercises, srs
from .models import CONFIDENCE_COL, CONFIDENCE_MAX, Card, blend_confidence

# "due": words FSRS says are due now (most forgotten first), then new words.
MODES = ("due", "topic", "mix", "difficult")
ORDERS = ("weighted", "lowest_confidence_first", "random", "oldest_first")

# Direction is named by ROLE, not by language code: one app serves several
# language pairs (German/English, English/Persian, ...), so "de_en" could not
# mean anything in a Persian workbook. "term" is the word being learned
# (column A), "native" its gloss (column B). See models.LanguagePair.
DIRECTIONS = ("mixed", "term_to_native", "native_to_term")

# How a card is answered (exercises.py). "mixed" picks one per card showing
# among those the card supports; a card that can't do the chosen kind (no
# example sentence containing the word, nothing to build distractors from)
# falls back: blank -> choice -> flashcard. "article" decks contain only
# der/die/das nouns (service.Service._build filters them), so it never falls back.
EXERCISES = ("flashcard", "choice", "blank", "recall", "article", "mixed")
# What each choice falls back to, in order; "flashcard" is always possible.
_FALLBACK = {"choice": ("choice",), "blank": ("blank", "choice"), "recall": ("recall",),
             "article": ("article", "choice")}


@dataclass(frozen=True)
class Exercise:
    kind: str                               # exercises.KINDS
    choice: exercises.Choice | None = None  # choice and article
    blank: exercises.Blank | None = None    # blank and recall

# Sampling weight = (5.5 - score) ** 2, so score 1 is 81x likelier to be drawn
# than score 5, and an ungraded word outranks everything.
#
# The exponent is NOT decoration. With a linear weight and a realistic library
# (say 30 weak words at score 1 among 300 mostly-mastered ones) the sheer count
# of strong words wins: weak words take only ~33% of the draws, i.e. 7 of a
# 20-card deck. Squaring flips that to ~69%, i.e. 14 of 20, which is what
# "weakest words come up most" actually has to mean. Raise it to sharpen further.
WEIGHT_BASE_MAX = CONFIDENCE_MAX + 0.5
WEIGHT_EXPONENT = 2.0


def selection_weight(confidence: float | None) -> float:
    """How strongly a card should be favoured when drawing a deck.

    Monotonically decreasing in confidence: the weaker the word, the likelier it
    is drawn, and an ungraded word (never seen) outranks every graded one.
    """
    base = WEIGHT_BASE_MAX if confidence is None else WEIGHT_BASE_MAX - confidence
    return base ** WEIGHT_EXPONENT


def build_deck(
    cards: list[Card],
    *,
    mode: str,
    topics: list[str] | None = None,
    difficult_threshold: int = 2,
    order: str = "weighted",
    seed: int | None = None,
    limit: int | None = None,
    now: datetime | None = None,
    local_tz: tzinfo | None = None,
) -> list[Card]:
    """Filter and order the cards for one review session.

    mode:
      "due"       -> words whose FSRS schedule is due (lowest estimated recall
                     first), then never-scheduled words in `order`; words
                     scheduled for later are left out. Honours `topics` if given.
                     Needs `now`.
      "topic"     -> only the sheets in `topics`
      "mix"       -> every topic
      "difficult" -> only cards with confidence <= difficult_threshold
                     (ungraded cards are excluded -- there is no score to be
                     "difficult" yet); still honours `topics` if given

    `limit` caps the deck at that many unique words. It is applied AFTER ordering,
    so with order="weighted" the truncation *is* the weighted draw: the keys in
    `_order` make taking the first `limit` a weighted sample without replacement.
    """
    if mode not in MODES:
        raise ValueError(f"unknown mode: {mode!r}")
    if order not in ORDERS:
        raise ValueError(f"unknown order: {order!r}")
    if limit is not None and limit < 1:
        raise ValueError(f"limit must be >= 1, got {limit}")

    selected = list(cards)

    if topics and mode in ("topic", "difficult", "due"):
        wanted = set(topics)
        selected = [c for c in selected if c.topic in wanted]

    if mode == "difficult":
        selected = [
            c for c in selected
            if c.confidence is not None and c.confidence <= difficult_threshold
        ]

    if mode == "due":
        if now is None:
            raise ValueError("mode 'due' needs `now`")
        ordered = _due_first(selected, order, seed, now, local_tz)
    else:
        ordered = _order(selected, order, seed)
    return ordered if limit is None else ordered[:limit]


def _due_first(cards: list[Card], order: str, seed: int | None,
               now: datetime, local_tz: tzinfo | None) -> list[Card]:
    scheduler = srs.make_scheduler(fuzz=False)
    due = [c for c in cards if srs.status(c.schedule, now, local_tz) == srs.DUE]
    # Most forgotten first, so a session cut short by the limit still covers
    # the words closest to being lost. Ties (e.g. same-minute learning steps) by due time.
    due.sort(key=lambda c: (srs.retrievability(c.schedule, now, scheduler) or 0.0, c.schedule.due))
    new = [c for c in cards if c.schedule is None]
    return due + _order(new, order, seed)


def _order(cards: list[Card], order: str, seed: int | None) -> list[Card]:
    rng = random.Random(seed)
    if order == "weighted":
        # Efraimidis-Spirakis: key = U ** (1 / w). Taking the k largest keys is
        # exactly a weighted sample without replacement, and sorting by key gives
        # the weighted ordering for free -- so one pass serves both `limit` and
        # the "weakest words first, but not deterministically" requirement.
        # Do not swap this for random.choices: that samples WITH replacement and
        # would put the same word in a deck twice.
        keys = {c.id: rng.random() ** (1.0 / selection_weight(c.confidence)) for c in cards}
        return sorted(cards, key=lambda c: keys[c.id], reverse=True)

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


def pick_next_repeat(
    scores: dict[str, float | None],
    *,
    exclude: str | None = None,
    seed: int | None = None,
    draw: int = 0,
) -> str | None:
    """Choose the next card to re-drill, favouring whichever is weakest *now*.

    `scores` maps card id -> its current in-session score, not the on-disk value.
    That is the point: when a word improves its weight drops immediately, so the
    words that did not improve take over the remaining repeats.

    `exclude` is the card just finished; it is skipped unless it is the only one
    left, so a word is never shown twice in a row. `draw` makes successive picks
    differ under a fixed `seed` while staying reproducible.
    """
    if not scores:
        return None
    ids = [i for i in scores if i != exclude] or list(scores)
    rng = random.Random(f"{seed}:repeat:{draw}")
    weights = [selection_weight(scores[i]) for i in ids]
    return rng.choices(ids, weights=weights, k=1)[0]


def pick_direction(pref: str, card_id: str, seed: int | None = None) -> str:
    """Resolve the front/back direction for one card.

    Returns "term_to_native" (show the word, recall the meaning) or
    "native_to_term" (the reverse). "mixed" -> deterministic per (seed, card) so a
    card keeps its direction until it is graded and re-drawn, but different cards
    vary.
    """
    if pref in ("term_to_native", "native_to_term"):
        return pref
    # String seed: a tuple seed is deprecated (hashing-based) since Python 3.9.
    rng = random.Random(f"{seed}:{card_id}")
    return "term_to_native" if rng.random() < 0.5 else "native_to_term"


# --------------------------------------------------------------------------- #
# One review session
# --------------------------------------------------------------------------- #

@dataclass
class ReviewEntry:
    """One grade given in the session, for the review log (service.write)."""

    card_id: str
    grade: int
    reviewed_at: datetime
    confidence_before: float | None
    confidence_after: float
    scheduled: bool          # False: an in-session repeat FSRS did not see
    due_after: datetime | None


@dataclass
class ReviewSession:
    """The state of one review session, from the first card to the summary.

    Two phases, on purpose. Every drawn word is shown once (the first pass)
    before any repeat, so capping the session at N words still means you *see*
    all N; only then do repeats start, re-weighted on every pick.

    Each grade updates the word's two scores (srs.py explains why there are
    two) under different rules:

    * Confidence: every grade, by averaging (models.blend_confidence). Repeats
      compound on the session's own value, never the on-disk one -- averaging
      against the file every time would make drilling a word a no-op.
    * FSRS schedule: the word's first grade of the session, and after that
      only grades given when FSRS itself has the word due again (a learning
      step after "Again", minutes later). NOT every repeat: drilling a word
      four times in a row is not four spaced recalls, and feeding FSRS four
      same-day "Easy" reviews pushed a brand-new word a month out in testing.
      The first grade counts even when the word was not due (topic/mix decks):
      FSRS handles an early review by growing stability less.
    """

    deck: list[Card]
    direction: str = "mixed"
    repeat: bool = False
    seed: int = 0
    scheduler: fsrs.Scheduler = field(default_factory=srs.make_scheduler)
    exercise: str = "flashcard"
    # Where multiple-choice distractors come from: the whole workbook, not just
    # the deck, so a 5-word deck still gets varied options. None = the deck.
    pool: list[Card] | None = None

    def __post_init__(self) -> None:
        if not self.deck:
            raise ValueError("cannot start a session with no cards")
        if self.direction not in DIRECTIONS:
            raise ValueError(f"unknown direction: {self.direction!r}")
        if self.exercise not in EXERCISES:
            raise ValueError(f"unknown exercise: {self.exercise!r}")
        if self.pool is None:
            self.pool = list(self.deck)
        self.cards: dict[str, Card] = {c.id: c for c in self.deck}
        self.first_pass: list[str] = [c.id for c in self.deck]
        self.pending: list[str] = []  # words still below 5, re-drilled after the first pass
        self.draw = 0
        self.current: str | None = self.deck[0].id
        self.confidence: dict[str, float] = {}       # in-session confidence of graded words
        self.schedules: dict[str, srs.Schedule] = {}  # in-session schedule of graded words
        self.log: list[ReviewEntry] = []
        # One "turn" per card showing; a repeat of the same word is a new turn
        # and may get a different exercise. Reset by _advance.
        self.turn = 0
        self._exercise: Exercise | None = None
        self.result: dict | None = None  # outcome of check() for this turn
        # Previews use an unfuzzed copy: a button whose "next review" text
        # changed between two looks would read as a bug. Real grades are fuzzed.
        self._preview_scheduler = fsrs.Scheduler(
            parameters=self.scheduler.parameters,
            desired_retention=self.scheduler.desired_retention,
            learning_steps=self.scheduler.learning_steps,
            relearning_steps=self.scheduler.relearning_steps,
            maximum_interval=self.scheduler.maximum_interval,
            enable_fuzzing=False,
        )

    # --- reading -------------------------------------------------------------

    @property
    def finished(self) -> bool:
        return self.current is None

    @property
    def current_card(self) -> Card | None:
        return None if self.current is None else self.cards[self.current]

    @property
    def graded_ids(self) -> list[str]:
        """Graded words in the order they were first graded."""
        return list(dict.fromkeys(e.card_id for e in self.log))

    def effective_confidence(self, card_id: str) -> float | None:
        """The word's confidence as of now: the session's value once graded, else the file's."""
        return self.confidence.get(card_id, self.cards[card_id].confidence)

    def effective_schedule(self, card_id: str) -> srs.Schedule | None:
        return self.schedules.get(card_id, self.cards[card_id].schedule)

    def direction_for(self, card_id: str) -> str:
        return pick_direction(self.direction, card_id, self.seed)

    def counts_for_schedule(self, card_id: str, now: datetime) -> bool:
        """Whether a grade now would update FSRS (see the class docstring)."""
        if card_id not in self.schedules:
            return True
        return srs.status(self.schedules[card_id], now) == srs.DUE

    def preview(self, card_id: str, grade: int, now: datetime) -> tuple[float, srs.Schedule | None]:
        """What `grade` would set, shown on the grade buttons before pressing them.

        The schedule is None when this grade would not reach FSRS.
        """
        confidence = blend_confidence(self.effective_confidence(card_id), grade)
        if not self.counts_for_schedule(card_id, now):
            return confidence, None
        return confidence, srs.review(self.effective_schedule(card_id), grade, now, self._preview_scheduler)

    # --- exercises -----------------------------------------------------------

    def current_exercise(self) -> Exercise:
        """The exercise for the card showing now; fixed for the whole turn."""
        cid = self._require_current()
        if self._exercise is None:
            card = self.cards[cid]
            rng = random.Random(f"{self.seed}:exercise:{self.turn}:{cid}")
            wanted = set(_FALLBACK.get(self.exercise, ())) if self.exercise != "mixed" else set(_FALLBACK)
            made: dict[str, Exercise] = {"flashcard": Exercise("flashcard")}
            if "blank" in wanted and (blank := exercises.make_blank(card)):
                made["blank"] = Exercise("blank", blank=blank)
            if "recall" in wanted and (recall := exercises.make_recall(card)):
                made["recall"] = Exercise("recall", blank=recall)
            if "article" in wanted and (article := exercises.make_article(card)):
                made["article"] = Exercise("article", choice=article)
            if "choice" in wanted and (choice := exercises.make_choice(card, self.pool, self.direction_for(cid), rng)):
                made["choice"] = Exercise("choice", choice=choice)
            if self.exercise == "mixed":
                self._exercise = made[rng.choice(sorted(made))]
            else:
                kind = next((k for k in _FALLBACK.get(self.exercise, ()) if k in made), "flashcard")
                self._exercise = made[kind]
        return self._exercise

    def check(self, answer) -> dict:
        """Check an answer to the current exercise: a typed text (blank) or an
        option index (choice). Once per turn; the result proposes a grade."""
        ex = self.current_exercise()
        if self.result is not None:
            return self.result
        if ex.kind in ("choice", "article"):
            try:
                picked = int(answer)
            except (TypeError, ValueError) as exc:
                raise ValueError("pick one of the options") from exc
            if not 0 <= picked < len(ex.choice.options):
                raise ValueError("pick one of the options")
            verdict = exercises.check_choice(ex.choice, picked)
            self.result = {"verdict": verdict, "picked": picked,
                           "expected": ex.choice.options[ex.choice.answer_index]}
        elif ex.kind in ("blank", "recall"):
            given = "" if answer is None else str(answer)
            verdict = exercises.check_typed(given, ex.blank)
            self.result = {"verdict": verdict, "given": given, "expected": ex.blank.answer}
        else:
            raise ValueError("a flashcard has nothing to check")
        self.result["suggested"] = exercises.SUGGESTED_GRADE[(ex.kind, verdict)]
        return self.result

    # --- actions -------------------------------------------------------------

    def grade(self, grade: int, now: datetime) -> None:
        """Grade the current word 1-5 and move on."""
        cid = self._require_current()
        before = self.effective_confidence(cid)
        new = blend_confidence(before, grade)  # validates 1..5
        scheduled = self.counts_for_schedule(cid, now)
        if scheduled:
            self.schedules[cid] = srs.review(self.effective_schedule(cid), grade, now, self.scheduler)
        self.confidence[cid] = new
        due = self.schedules[cid].due if cid in self.schedules else None
        self.log.append(ReviewEntry(cid, grade, now, before, new, scheduled, due))
        # Mastery is the *stored* score, not the button pressed: after averaging,
        # one press of 5 on a weak word lands below 5 and it should come round again.
        self._advance(repeat_current=self.repeat and new < CONFIDENCE_MAX)

    def skip(self) -> None:
        """Move on without grading; a skipped repeat is done for this session."""
        self._require_current()
        self._advance(repeat_current=False)

    def end(self) -> None:
        self.current = None

    # --- results -------------------------------------------------------------

    def cell_updates(self) -> dict[tuple[str, int], dict[str, object]]:
        """(sheet, row) -> {column: value} for writeback.apply_updates.

        Confidence only where it actually changed (an average that lands back
        on the file's value is not a change); the schedule for every word FSRS
        reviewed, since any review moves it.
        """
        out: dict[tuple[str, int], dict[str, object]] = {}
        for cid in self.graded_ids:
            card = self.cards[cid]
            cols: dict[str, object] = {}
            if cid in self.schedules:
                cols.update(srs.schedule_to_cells(self.schedules[cid]))
            if self.confidence[cid] != card.confidence:
                cols[CONFIDENCE_COL] = self.confidence[cid]
            if cols:
                out[(card.sheet, card.row)] = cols
        return out

    # --- internals -----------------------------------------------------------

    def _require_current(self) -> str:
        if self.current is None:
            raise ValueError("the session has ended")
        return self.current

    def _advance(self, *, repeat_current: bool) -> None:
        cid = self.current
        self.turn += 1
        self._exercise = None
        self.result = None
        if self.first_pass and self.first_pass[0] == cid:
            self.first_pass.pop(0)
        if repeat_current:
            if cid not in self.pending:
                self.pending.append(cid)
        elif cid in self.pending:
            self.pending.remove(cid)  # reached 5 (or was skipped): done for this session

        if self.first_pass:
            self.current = self.first_pass[0]
            return
        if self.pending:
            # Re-weight on the session's current scores, not the on-disk ones: a
            # word that just improved yields its turn to the words that did not.
            self.draw += 1
            scores = {i: self.effective_confidence(i) for i in self.pending}
            self.current = pick_next_repeat(scores, exclude=cid, seed=self.seed, draw=self.draw)
            return
        self.current = None
