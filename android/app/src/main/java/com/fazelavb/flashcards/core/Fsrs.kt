package com.fazelavb.flashcards.core

import java.time.Duration
import java.time.Instant
import kotlin.math.E
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.round
import kotlin.random.Random

/*
 * FSRS-6, ported line by line from the Python `fsrs` package (6.x, the version
 * the desktop app uses), so the phone and the desktop schedule a word the same
 * way. FsrsTest checks it against numbers produced by the Python package.
 *
 * Two Python details matter for identical results:
 *  - round() is banker's rounding: round() here, not Math.round();
 *  - timedelta.days floors: floorDiv here, not Duration.toDays().
 */

enum class FsrsState { Learning, Review, Relearning }

/** Again / Hard / Good / Easy = 1..4, as in FSRS. */
enum class Rating(val value: Int) { Again(1), Hard(2), Good(3), Easy(4) }

data class FsrsCard(
    val state: FsrsState = FsrsState.Learning,
    val step: Int? = if (state == FsrsState.Learning) 0 else null,
    val stability: Double? = null,
    val difficulty: Double? = null,
    val due: Instant = Instant.EPOCH,
    val lastReview: Instant? = null,
)

val FSRS_DEFAULT_PARAMETERS = doubleArrayOf(
    0.212, 1.2931, 2.3065, 8.2956, 6.4133, 0.8334, 3.0194, 0.001, 1.8722, 0.1666, 0.796,
    1.4835, 0.0614, 0.2629, 1.6483, 0.6014, 1.8729, 0.5425, 0.0912, 0.0658, 0.1542,
)

private const val STABILITY_MIN = 0.001
private const val MIN_DIFFICULTY = 1.0
private const val MAX_DIFFICULTY = 10.0
private const val SECONDS_PER_DAY = 86_400L

private class FuzzRange(val start: Double, val end: Double, val factor: Double)

private val FUZZ_RANGES = listOf(
    FuzzRange(2.5, 7.0, 0.15),
    FuzzRange(7.0, 20.0, 0.1),
    FuzzRange(20.0, Double.POSITIVE_INFINITY, 0.05),
)

class FsrsScheduler(
    val parameters: DoubleArray = FSRS_DEFAULT_PARAMETERS,
    val desiredRetention: Double = 0.9,
    val learningSteps: List<Duration> = listOf(Duration.ofMinutes(1), Duration.ofMinutes(10)),
    val relearningSteps: List<Duration> = listOf(Duration.ofMinutes(10)),
    val maximumInterval: Int = 36500,
    val enableFuzzing: Boolean = true,
    private val random: Random = Random.Default,
) {
    private val p = parameters
    private val decay = -p[20]
    private val factor = 0.9.pow(1 / decay) - 1

    init {
        require(parameters.size == 21) { "Expected 21 parameters, got ${parameters.size}." }
    }

    /** The same scheduler without fuzzing: for previews that must not change between two looks. */
    fun withoutFuzz() = FsrsScheduler(parameters, desiredRetention, learningSteps, relearningSteps, maximumInterval, false)

    fun retrievability(card: FsrsCard, now: Instant): Double {
        val last = card.lastReview ?: return 0.0
        val stability = card.stability ?: return 0.0
        val elapsedDays = max(0L, daysBetween(last, now))
        return (1 + factor * elapsedDays / stability).pow(decay)
    }

    fun review(card: FsrsCard, rating: Rating, now: Instant): FsrsCard {
        val daysSince = card.lastReview?.let { daysBetween(it, now) }
        var state = card.state
        var step = card.step
        var stability = card.stability
        var difficulty = card.difficulty
        val interval: Duration

        when (card.state) {
            FsrsState.Learning, FsrsState.Relearning -> {
                val steps = if (card.state == FsrsState.Learning) learningSteps else relearningSteps
                val s = checkNotNull(step) { "a (re)learning card needs a step" }
                if (stability == null || difficulty == null) {
                    stability = initialStability(rating)
                    difficulty = initialDifficulty(rating, clamp = true)
                } else if (daysSince != null && daysSince < 1) {
                    stability = shortTermStability(stability, rating)
                    difficulty = nextDifficulty(difficulty, rating)
                } else {
                    stability = nextStability(difficulty, stability, retrievability(card, now), rating)
                    difficulty = nextDifficulty(difficulty, rating)
                }
                if (steps.isEmpty() || (s >= steps.size && rating != Rating.Again)) {
                    state = FsrsState.Review
                    step = null
                    interval = days(nextInterval(stability))
                } else when (rating) {
                    Rating.Again -> {
                        step = 0
                        interval = steps[0]
                    }
                    Rating.Hard -> interval = when {
                        s == 0 && steps.size == 1 -> steps[0].times(1.5)
                        s == 0 && steps.size >= 2 -> steps[0].plus(steps[1]).times(0.5)
                        else -> steps[s]
                    }
                    Rating.Good -> if (s + 1 == steps.size) {
                        state = FsrsState.Review
                        step = null
                        interval = days(nextInterval(stability))
                    } else {
                        step = s + 1
                        interval = steps[s + 1]
                    }
                    Rating.Easy -> {
                        state = FsrsState.Review
                        step = null
                        interval = days(nextInterval(stability))
                    }
                }
            }
            FsrsState.Review -> {
                val s0 = checkNotNull(stability)
                val d0 = checkNotNull(difficulty)
                stability = if (daysSince != null && daysSince < 1) shortTermStability(s0, rating)
                else nextStability(d0, s0, retrievability(card, now), rating)
                difficulty = nextDifficulty(d0, rating)
                if (rating == Rating.Again && relearningSteps.isNotEmpty()) {
                    state = FsrsState.Relearning
                    step = 0
                    interval = relearningSteps[0]
                } else {
                    interval = days(nextInterval(stability))
                }
            }
        }

        val finalInterval = if (enableFuzzing && state == FsrsState.Review) fuzzed(interval) else interval
        return FsrsCard(state, step, stability, difficulty, due = now.plus(finalInterval), lastReview = now)
    }

    // --- the formulas ------------------------------------------------------

    private fun clampDifficulty(d: Double) = min(max(d, MIN_DIFFICULTY), MAX_DIFFICULTY)
    private fun clampStability(s: Double) = max(s, STABILITY_MIN)

    private fun initialStability(rating: Rating) = clampStability(p[rating.value - 1])

    private fun initialDifficulty(rating: Rating, clamp: Boolean): Double {
        val d = p[4] - E.pow(p[5] * (rating.value - 1)) + 1
        return if (clamp) clampDifficulty(d) else d
    }

    private fun nextInterval(stability: Double): Int {
        val raw = stability / factor * (desiredRetention.pow(1 / decay) - 1)
        return round(raw).toInt().coerceAtLeast(1).coerceAtMost(maximumInterval)
    }

    private fun shortTermStability(stability: Double, rating: Rating): Double {
        var increase = E.pow(p[17] * (rating.value - 3 + p[18])) * stability.pow(-p[19])
        if (rating != Rating.Again) increase = max(increase, 1.0)
        return clampStability(stability * increase)
    }

    private fun nextDifficulty(difficulty: Double, rating: Rating): Double {
        val delta = -(p[6] * (rating.value - 3))
        val damped = difficulty + (10.0 - difficulty) * delta / 9.0
        val reverted = p[7] * initialDifficulty(Rating.Easy, clamp = false) + (1 - p[7]) * damped
        return clampDifficulty(reverted)
    }

    private fun nextStability(difficulty: Double, stability: Double, retrievability: Double, rating: Rating): Double {
        val next = if (rating == Rating.Again) {
            val longTerm = p[11] * difficulty.pow(-p[12]) * ((stability + 1).pow(p[13]) - 1) *
                E.pow((1 - retrievability) * p[14])
            val shortTerm = stability / E.pow(p[17] * p[18])
            min(longTerm, shortTerm)
        } else {
            val hardPenalty = if (rating == Rating.Hard) p[15] else 1.0
            val easyBonus = if (rating == Rating.Easy) p[16] else 1.0
            stability * (1 + E.pow(p[8]) * (11 - difficulty) * stability.pow(-p[9]) *
                (E.pow((1 - retrievability) * p[10]) - 1) * hardPenalty * easyBonus)
        }
        return clampStability(next)
    }

    private fun fuzzed(interval: Duration): Duration {
        val intervalDays = Math.floorDiv(interval.seconds, SECONDS_PER_DAY)
        if (intervalDays < 2.5) return interval
        var delta = 1.0
        for (r in FUZZ_RANGES) delta += r.factor * max(min(intervalDays.toDouble(), r.end) - r.start, 0.0)
        var minIvl = round(intervalDays - delta).toInt()
        var maxIvl = round(intervalDays + delta).toInt()
        minIvl = max(2, minIvl)
        maxIvl = min(maxIvl, maximumInterval)
        minIvl = min(minIvl, maxIvl)
        val fuzzedDays = min(round(random.nextDouble() * (maxIvl - minIvl + 1) + minIvl).toInt(), maximumInterval)
        return days(fuzzedDays)
    }

    private fun days(n: Int): Duration = Duration.ofDays(n.toLong())

    private fun Duration.times(f: Double): Duration = Duration.ofNanos(round(toNanos() * f).toLong())
}

/** Whole days from a to b, floored like Python's timedelta.days. */
fun daysBetween(a: Instant, b: Instant): Long = Math.floorDiv(Duration.between(a, b).seconds, SECONDS_PER_DAY)
