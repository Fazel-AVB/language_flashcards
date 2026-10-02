"""Exercises built from the workbook itself: multiple choice, fill in the blank,
type the word (recall), listening (dictation) and the German article drill.

An exercise only changes *how you answer* a card. After it is checked, the
app proposes a grade (SUGGESTED_GRADE) and you confirm or override it with
the usual 1-5 buttons, so confidence averaging and FSRS (session.py, srs.py)
work exactly as for a self-graded flashcard. Deliberately not auto-graded:
"close" spellings and lucky guesses are your call, as before.

Pure functions, no I/O. Used by session.ReviewSession.
"""

from __future__ import annotations

import random
import re
import unicodedata
from dataclasses import dataclass

from .models import Card

KINDS = ("flashcard", "choice", "blank", "recall", "listen", "article")
ARTICLES = ("der", "die", "das")
CORRECT, CLOSE, WRONG = "correct", "close", "wrong"

# Proposed grade per outcome. Recognising the answer among four (choice) and
# producing it (blank) both count as a successful recall, i.e. FSRS "Good";
# press 5 yourself when it was effortless. "close" (typo, missing accent) is
# a recall with a flaw: 3, still Good for FSRS but a lower confidence.
SUGGESTED_GRADE = {
    ("choice", CORRECT): 4, ("choice", WRONG): 1,
    ("blank", CORRECT): 4, ("blank", CLOSE): 3, ("blank", WRONG): 1,
    ("recall", CORRECT): 4, ("recall", CLOSE): 3, ("recall", WRONG): 1,
    ("listen", CORRECT): 4, ("listen", CLOSE): 3, ("listen", WRONG): 1,
    # A wrong article is not a forgotten word: you may well know what it means.
    # 2 ("shaky") keeps it coming back soon without resetting it like 1 would;
    # press 1 yourself if the word meant nothing to you.
    ("article", CORRECT): 4, ("article", WRONG): 2,
}

N_OPTIONS = 4
# A blank covering most of the sentence is not an exercise: on the
# "Idioms & Sentences" sheets the word IS the example sentence.
MAX_BLANK_SHARE = 0.6


@dataclass(frozen=True)
class Choice:
    """Pick the answer among `options`. The prompt is the card's front side."""

    options: list[str]
    answer_index: int
    answer_side: str  # "native" (options are meanings) or "term"


@dataclass(frozen=True)
class Blank:
    """The example sentence with the word cut out: before + ____ + after."""

    before: str
    after: str
    answer: str            # the text that was cut out (may be inflected: "riesiges")
    accepted: tuple[str, ...]  # also counted correct: the dictionary form ("riesig")


# --------------------------------------------------------------------------- #
# Multiple choice
# --------------------------------------------------------------------------- #

def make_choice(card: Card, pool: list[Card], direction: str, rng: random.Random) -> Choice | None:
    """Four options: the right answer plus three distractors from `pool`.

    Distractors come from the same language pair, preferring the same word
    type, then the same topic, so a noun is not trivially picked out among
    verbs. None when the pool has no other distinct answer.
    """
    side = "native" if direction == "term_to_native" else "term"
    answer = _side(card, side)
    if not answer:
        return None

    seen = {canon(answer)}
    candidates = [c for c in pool if c.id != card.id and c.languages == card.languages]
    rng.shuffle(candidates)
    # Stable sort after the shuffle: random within each tier, best tier first.
    candidates.sort(key=lambda c: (not _same_type(c, card), c.topic != card.topic))
    distractors = []
    for c in candidates:
        text = _side(c, side)
        if text and canon(text) not in seen:
            seen.add(canon(text))
            distractors.append(text)
        if len(distractors) == N_OPTIONS - 1:
            break
    if not distractors:
        return None

    options = distractors + [answer]
    rng.shuffle(options)
    return Choice(options=options, answer_index=options.index(answer), answer_side=side)


def check_choice(choice: Choice, picked: int) -> str:
    return CORRECT if picked == choice.answer_index else WRONG


# --------------------------------------------------------------------------- #
# Article drill (German nouns)
# --------------------------------------------------------------------------- #

def article_of(card: Card) -> str | None:
    """The noun's article if column D holds exactly der, die or das, else None.

    Anything else -- verbs' auxiliaries (haben/sein), "der/das" nouns with two
    genders, "—" -- is not drillable and keeps the card out of the article deck.
    """
    a = (card.article_aux or "").strip().lower()
    return a if a in ARTICLES else None


def make_article(card: Card) -> Choice | None:
    a = article_of(card)
    if a is None:
        return None
    return Choice(options=list(ARTICLES), answer_index=ARTICLES.index(a), answer_side="article")


# --------------------------------------------------------------------------- #
# Type the word (recall)
# --------------------------------------------------------------------------- #

def make_recall(card: Card) -> Blank | None:
    """See the meaning, type the word. A Blank with nothing around the gap, so
    check_typed applies the same leniency as fill-in-the-blank."""
    if not card.meaning:
        return None
    return _typed_word(card)


def make_listen(card: Card) -> Blank | None:
    """Hear the word (spoken by the browser, app.js), type it. Checked like recall;
    needs no meaning, since the word itself is the prompt."""
    return _typed_word(card)


def _typed_word(card: Card) -> Blank | None:
    if not card.term:
        return None
    variants = term_variants(card.term)
    article = article_of(card)
    # Typing the noun with its article ("die Kerze") is right, not wrong.
    with_article = [f"{article} {v}" for v in variants] if article else []
    accepted = tuple(dict.fromkeys(v for v in (*variants, *with_article) if canon(v) != canon(card.term)))
    return Blank(before="", after="", answer=card.term, accepted=accepted)


# --------------------------------------------------------------------------- #
# Fill in the blank
# --------------------------------------------------------------------------- #

def make_blank(card: Card) -> Blank | None:
    """Cut the word out of its example sentence (column G), or None if it isn't in there."""
    sentence = card.example_term or ""
    for variant in term_variants(card.term):
        span = _find(sentence, variant)
        if span is None:
            continue
        start, end = span
        if (end - start) > MAX_BLANK_SHARE * len(sentence.strip()):
            return None
        answer = sentence[start:end]
        accepted = tuple(dict.fromkeys(v for v in (card.term, *term_variants(card.term)) if canon(v) != canon(answer)))
        return Blank(before=sentence[:start], after=sentence[end:], answer=answer, accepted=accepted)
    return None


def check_typed(given: str, blank: Blank) -> str:
    """correct / close / wrong for a typed answer.

    correct: same text up to case, spacing, surrounding punctuation and the
             German spellings ae/oe/ue/ss for ä/ö/ü/ß; also the dictionary
             form when the sentence uses an inflected one.
    close:   only accents differ ("Kuhlschrank"), or one typo in a word of 5+
             letters. Shown as "check the spelling"; you decide the grade.
    """
    if not given or not given.strip():
        return WRONG
    targets = (blank.answer, *blank.accepted)
    g = canon(given)
    if any(g == canon(t) for t in targets):
        return CORRECT
    if any(_fold(g) == _fold(canon(t)) for t in targets):
        return CLOSE
    if any(len(canon(t)) >= 5 and _edit_distance_le1(g, canon(t)) for t in targets):
        return CLOSE
    return WRONG


_PLACEHOLDERS = re.compile(r"\s(?:someone|somebody|something|someone's|oneself|yourself|sb|sth)(?=\s)", re.IGNORECASE)


def term_variants(term: str) -> list[str]:
    """Forms of a headword worth looking for in a sentence.

    "sich freuen (auf)" -> ["sich freuen (auf)", "sich freuen", "freuen"];
    "Kuli / Kugelschreiber" -> both halves. Longest first, so a whole phrase
    wins over one of its words.
    """
    out = [term.strip()]
    no_paren = re.sub(r"\s*\([^)]*\)", "", term).strip()
    out.append(no_paren)
    for part in re.split(r"\s*[/,;]\s*", no_paren):
        out.append(part)
        # German reflexive verbs: the sentence conjugates "sich" ("ich freue mich").
        if part.lower().startswith("sich "):
            out.append(part[5:])
        # English dictionary forms: "to get whiplash", "lash out at someone",
        # "tapping into something": the sentence has a real object instead.
        bare = _PLACEHOLDERS.sub(" ", " " + part + " ").strip()
        bare = re.sub(r"^to\s+", "", bare, flags=re.IGNORECASE)
        if bare:
            out.append(bare)
    seen, result = set(), []
    for v in sorted((v for v in out if v), key=len, reverse=True):
        if v.lower() not in seen:
            seen.add(v.lower())
            result.append(v)
    return result


# --------------------------------------------------------------------------- #
# helpers
# --------------------------------------------------------------------------- #

_GERMAN = str.maketrans({"ä": "ae", "ö": "oe", "ü": "ue", "ß": "ss"})
# Persian is often typed with Arabic code points (ي, ك) on non-Persian keyboards;
# the zero-width non-joiner is spelling-relevant but invisible, so not a mistake to judge.
_PERSIAN = str.maketrans({"ي": "ی", "ك": "ک", "‌": ""})


def canon(text: str) -> str:
    """Comparison form: NFC, case-folded, German ä->ae etc., Persian letters unified,
    whitespace collapsed, punctuation at the ends dropped."""
    t = unicodedata.normalize("NFC", text).casefold().translate(_GERMAN).translate(_PERSIAN)
    t = re.sub(r"\s+", " ", t).strip()
    return re.sub(r"^[\W_]+|[\W_]+$", "", t)


def _fold(text: str) -> str:
    # Accents removed: "kuhlschrank" == "kühlschrank" here, but not in canon().
    return "".join(ch for ch in unicodedata.normalize("NFKD", text) if not unicodedata.combining(ch))


def _find(sentence: str, term: str) -> tuple[int, int] | None:
    """Span of `term` in `sentence`, widened to whole words.

    1. The term itself, starting at a word boundary, case-insensitive; the
       span runs to the end of that word, so "riesig" blanks "riesiges".
    2. A single-word term: a word sharing its first len-2 letters (min 4),
       so "schreiben" finds "schreibe". Irregular forms ("ging" for "gehen")
       are not found; that card then gets another exercise.
    """
    if not term or not sentence:
        return None
    m = re.search(r"(?<!\w)" + re.escape(term), sentence, re.IGNORECASE)
    if m:
        end = m.end()
        while end < len(sentence) and (sentence[end].isalnum() or sentence[end] == "‌"):
            end += 1
        return m.start(), end
    if " " in term:
        return None
    t = term.casefold()
    need = max(4, len(t) - 2)
    if len(t) < need:
        return None
    for w in re.finditer(r"\w+", sentence):
        if w.group().casefold()[:need] == t[:need]:
            return w.span()
    return None


def _edit_distance_le1(a: str, b: str) -> bool:
    if a == b:
        return True
    if abs(len(a) - len(b)) > 1:
        return False
    if len(a) > len(b):
        a, b = b, a
    i = 0
    while i < len(a) and a[i] == b[i]:
        i += 1
    if len(a) == len(b):  # one substitution
        return a[i + 1:] == b[i + 1:]
    return a[i:] == b[i + 1:]  # one insertion


def _side(card: Card, side: str) -> str:
    return (card.meaning if side == "native" else card.term) or ""


def _same_type(a: Card, b: Card) -> bool:
    return bool(a.word_type) and (a.word_type or "").strip().lower() == (b.word_type or "").strip().lower()
