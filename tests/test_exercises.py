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


# --- type the word and the article drill -------------------------------------

def test_recall_accepts_article_and_variants(cards):
    stift = card(cards, "Stift")                      # article "der"
    r = ex.make_recall(stift)
    assert r.answer == "Stift"
    assert ex.check_typed("Stift", r) == ex.CORRECT
    assert ex.check_typed("der Stift", r) == ex.CORRECT
    assert ex.check_typed("Stft", r) == ex.CLOSE      # one letter missing in a 5-letter word
    assert ex.check_typed("Kuli", r) == ex.WRONG
    lash = ex.make_recall(replace(stift, term="lash out at someone", article_aux=None))
    assert ex.check_typed("lash out at", lash) == ex.CORRECT


@pytest.mark.parametrize("article,expected", [
    ("der", "der"), (" Die ", "die"), ("das", "das"),
    ("sein", None), ("der/das", None), ("—", None), (None, None),
])
def test_article_of(cards, article, expected):
    assert ex.article_of(replace(cards[0], article_aux=article)) == expected


def test_article_exercise_in_session(cards):
    stift = card(cards, "Stift")
    s = session([stift], cards, "article")
    e = s.current_exercise()
    assert e.kind == "article" and e.choice.options == ["der", "die", "das"]
    res = s.check(2)                                  # "das": wrong
    assert res["verdict"] == ex.WRONG and res["expected"] == "der" and res["suggested"] == 2


def test_recall_in_session(cards):
    s = session([card(cards, "werden")], cards, "recall")
    assert s.current_exercise().kind == "recall"
    assert s.check("Werden")["suggested"] == 4


# --- listening ---------------------------------------------------------------

def test_listen_needs_a_voice(cards):
    werden = card(cards, "werden")
    with_voice = ReviewSession([werden], seed=1, exercise="listen", can_listen=True, scheduler=make_scheduler(fuzz=False))
    assert with_voice.current_exercise().kind == "listen"
    assert with_voice.check("werden")["verdict"] == ex.CORRECT
    # No voice: the deck still works, as "type the word".
    without = ReviewSession([werden], seed=1, exercise="listen", can_listen=False, scheduler=make_scheduler(fuzz=False))
    assert without.current_exercise().kind == "recall"


def test_listen_works_without_meaning(cards):
    assert ex.make_listen(replace(cards[0], meaning="")) is not None
    assert ex.make_recall(replace(cards[0], meaning="")) is None


# --- sentence scramble -------------------------------------------------------

@pytest.fixture
def scr(cards):
    """Build a scramble of `sentence` on an otherwise ordinary card."""
    return lambda sentence, seed=0: ex.make_scramble(replace(cards[0], example_term=sentence), random.Random(seed))


def test_scramble_shape(scr):
    s = scr("Ich schreibe mit einem Stift.")
    assert s.answer == ["Ich", "schreibe", "mit", "einem", "Stift"] and s.end == "."
    assert sorted(s.tiles) == sorted(s.answer) and s.tiles != s.answer


@pytest.mark.parametrize("sentence", [None, "Zu kurz hier.", " ".join(["Wort"] * 4) + ".", " ".join(f"w{i}" for i in range(17))])
def test_scramble_not_possible(scr, sentence):
    assert scr(sentence) is None    # missing, under 4 words, all identical, over 16 words


def order_for(s, words):
    """Tile indices that spell `words` (identical words: any matching tile)."""
    left = list(range(len(s.tiles)))
    out = []
    for w in words:
        i = next(i for i in left if s.tiles[i] == w)
        left.remove(i)
        out.append(i)
    return out


def test_scramble_check(scr):
    s = scr("Die Milch ist im Kühlschrank.")
    assert ex.check_scramble(s, order_for(s, s.answer)) == ex.CORRECT
    swapped = ["Die", "Milch", "im", "ist", "Kühlschrank"]
    assert ex.check_scramble(s, order_for(s, swapped)) == ex.CLOSE
    assert ex.check_scramble(s, order_for(s, list(reversed(s.answer)))) == ex.WRONG
    with pytest.raises(ValueError):
        ex.check_scramble(s, [0, 0, 1, 2, 3])


def test_scramble_duplicate_words_in_either_order(scr):
    s = scr("the cat saw the dog")
    first_the, second_the = [i for i, t in enumerate(s.tiles) if t == "the"]
    rest = {t: i for i, t in enumerate(s.tiles) if t != "the"}
    order = [second_the, rest["cat"], rest["saw"], first_the, rest["dog"]]
    assert ex.check_scramble(s, order) == ex.CORRECT


def test_scramble_in_session(cards):
    s = session([card(cards, "Stift")], cards, "scramble")
    e = s.current_exercise()
    assert e.kind == "scramble"
    res = s.check(order_for(e.scramble, e.scramble.answer))
    assert res["verdict"] == ex.CORRECT and res["expected"] == "Ich schreibe mit einem Stift."
    bad = session([card(cards, "Stift")], cards, "scramble")
    with pytest.raises(ValueError):
        bad.check("0 1 2")
