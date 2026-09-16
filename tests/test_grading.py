"""The averaging rule: a grade is blended into the stored score, not substituted.

These pin the behaviour the UI depends on -- `app._grade_current` calls
`blend_confidence` and re-drills anything whose *blended* score is below 5, so a
change here silently changes how many times a word comes round.
"""

import pytest

from flashcards.models import (
    CONFIDENCE_MAX,
    blend_confidence,
    confidence_to_cell,
    format_confidence,
)


@pytest.mark.parametrize(
    "old,grade,expected",
    [
        (None, 3, 3.0),    # ungraded: nothing to average against, take the grade
        (1, 5, 3.0),
        (2, 5, 3.5),
        (4, 3, 3.5),
        (5, 5, 5.0),
        (1, 1, 1.0),
        (3.5, 4, 4.0),     # 3.75 snaps up to the half-step grid
        (3.5, 3, 3.5),     # 3.25 snaps up too: ties go up, consistently
    ],
)
def test_blend(old, grade, expected):
    assert blend_confidence(old, grade) == expected


def test_blend_rejects_out_of_range_grade():
    with pytest.raises(ValueError, match="out of range"):
        blend_confidence(3, 0)
    with pytest.raises(ValueError, match="out of range"):
        blend_confidence(3, 6)


def test_repeated_perfect_grades_converge_to_mastered():
    """Re-drilling a weak word with 5s must actually reach 5, not stall below it.

    This is why ties round up: with ties rounding down the sequence sticks at 4.5
    forever and the "repeat until mastered" loop never terminates.
    """
    score = 1.0
    for _ in range(10):
        score = blend_confidence(score, 5)
        if score >= CONFIDENCE_MAX:
            break
    assert score == CONFIDENCE_MAX


def test_one_bad_grade_does_not_erase_a_known_word():
    # The whole point of averaging: a single 1 on a mastered word costs 2 points,
    # not 4, so one bad day does not undo the history.
    assert blend_confidence(5, 1) == 3.0


@pytest.mark.parametrize(
    "value,text",
    [(3.0, "3"), (3.5, "3.5"), (1.0, "1"), (4.5, "4.5"), (5.0, "5")],
)
def test_cell_text_has_no_trailing_zero(value, text):
    assert confidence_to_cell(value) == text
    assert format_confidence(value) == text


def test_format_ungraded():
    assert format_confidence(None) == "ungraded"
