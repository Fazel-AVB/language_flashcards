"""Multiple choice and fill-in-the-blank (exercises.py) and how a session uses them."""

import random
from dataclasses import replace
from datetime import datetime, timezone

import pytest

from flashcards import exercises as ex
from flashcards.session import ReviewSession
from flashcards.srs import make_scheduler
from flashcards.workbook import load_cards

NOW = datetime(2026, 10, 3, 9, 0, tzinfo=timezone.utc)


@pytest.fixture
def cards(workbook_path):
    return load_cards(str(workbook_path)).cards


def card(cards, term):
    return next(c for c in cards if c.term == term)


# --- fill in the blank -------------------------------------------------------

@pytest.mark.parametrize("term,sentence,answer", [
    ("Stift", "Ich schreibe mit einem Stift.", "Stift"),
    ("riesig", "ein riesiges Haus", "riesiges"),                 # inflected: widened to the word
    ("schreiben", "Ich schreibe einen Bericht.", "schreibe"),    # stem match
    ("sich freuen (auf)", "Ich will mich freuen.", "freuen"),    # variants
    ("lash out at someone", "Stress made him lash out at his team.", "lash out at"),  # placeholder dropped
    ("to get whiplash", "You will get whiplash from this news.", "get whiplash"),
    ("the jealousy that gnaws you", "Don't let the jealousy that gnaws you destroy your peace.",
     "the jealousy that gnaws you"),
])
def test_blank_finds_the_word(cards, term, sentence, answer):
    b = ex.make_blank(replace(cards[0], term=term, example_term=sentence))
    assert b.answer == answer
    assert b.before + b.answer + b.after == sentence


@pytest.mark.parametrize("term,sentence", [
    ("gehen", "Ich ging nach Hause."),         # irregular form: not found
    ("anew", "anew"),                          # the word is the whole sentence
    ("Stift", None),                           # no example
])
def test_blank_not_possible(cards, term, sentence):
    assert ex.make_blank(replace(cards[0], term=term, example_term=sentence)) is None


def test_typed_answers():
    b = ex.Blank(before="Die Milch ist im ", after=".", answer="Kühlschrank", accepted=())
    assert ex.check_typed("kühlschrank", b) == ex.CORRECT
    assert ex.check_typed(" Kuehlschrank. ", b) == ex.CORRECT   # German ae spelling
    assert ex.check_typed("Kuhlschrank", b) == ex.CLOSE          # missing umlaut
    assert ex.check_typed("Kühlschank", b) == ex.CLOSE           # one typo
    assert ex.check_typed("Schrank", b) == ex.WRONG
    assert ex.check_typed("", b) == ex.WRONG
    infl = ex.Blank(before="ein ", after=" Haus", answer="riesiges", accepted=("riesig",))
    assert ex.check_typed("riesig", infl) == ex.CORRECT          # dictionary form accepted


def test_persian_letters_unified():
    b = ex.Blank(before="", after=" است", answer="یک", accepted=())
    assert ex.check_typed("يك", b) == ex.CORRECT                 # Arabic yeh and kaf


# --- multiple choice ---------------------------------------------------------

def test_choice_options(cards):
    stift = card(cards, "Stift")
    c = ex.make_choice(stift, cards, "term_to_native", random.Random(1))
    assert c.options[c.answer_index] == "pen"
    assert len(c.options) == len(set(c.options)) == 4
    assert ex.check_choice(c, c.answer_index) == ex.CORRECT
    reverse = ex.make_choice(stift, cards, "native_to_term", random.Random(1))
    assert reverse.options[reverse.answer_index] == "Stift"


def test_choice_prefers_same_word_type(cards):
    nouns = [replace(c, word_type="Noun", row=100 + i, term=f"N{i}", meaning=f"n{i}") for i, c in enumerate(cards * 2)]
    verbs = [replace(c, word_type="Verb", row=200 + i, term=f"V{i}", meaning=f"v{i}") for i, c in enumerate(cards * 2)]
    target = replace(cards[0], word_type="Noun")
    c = ex.make_choice(target, nouns + verbs, "term_to_native", random.Random(3))
    assert all(o.startswith("n") or o == target.meaning for o in c.options)


def test_choice_needs_another_answer(cards):
    assert ex.make_choice(cards[0], [cards[0]], "term_to_native", random.Random(0)) is None


# --- in a session ------------------------------------------------------------

def session(deck, pool, kind):
    return ReviewSession(deck, direction="term_to_native", seed=1, exercise=kind, pool=pool,
                         scheduler=make_scheduler(fuzz=False))


def test_session_choice_then_grade(cards):
    s = session([card(cards, "Stift")], cards, "choice")
    e = s.current_exercise()
    assert e.kind == "choice"
    res = s.check(e.choice.answer_index)
    assert res["verdict"] == ex.CORRECT and res["suggested"] == 4
    assert s.check(0) is res            # once per turn
    s.grade(res["suggested"], NOW)
    assert s.finished and s.confidence[card(cards, "Stift").id] == 2.5


def test_session_blank_falls_back_to_choice(cards):
    werden = card(cards, "werden")      # example "Sie will Ärztin werden." contains it
    stift = replace(card(cards, "Stift"), example_term="Ganz anderer Satz.")
    s = session([werden, stift], cards, "blank")
    assert s.current_exercise().kind == "blank"
    assert s.check("werden")["verdict"] == ex.CORRECT
    s.grade(4, NOW)
    assert s.current_exercise().kind == "choice"   # no blank possible for this one


def test_session_rejects_bad_answers(cards):
    s = session([card(cards, "Stift")], cards, "choice")
    with pytest.raises(ValueError):
        s.check(9)
    f = session([card(cards, "Stift")], cards, "flashcard")
    with pytest.raises(ValueError):
        f.check("x")


def test_mixed_varies_by_turn(cards):
    s = ReviewSession(cards, seed=5, exercise="mixed", repeat=False, scheduler=make_scheduler(fuzz=False))
    kinds = set()
    while not s.finished:
        kinds.add(s.current_exercise().kind)
        s.skip()
    assert len(kinds) >= 2
