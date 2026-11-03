package com.fazelavb.flashcards.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/** Expected values produced by the Python fsrs 6.3.2 package (the desktop app's scheduler), fuzzing off. */
class FsrsTest {
    private data class Step(val rating: Int, val seconds: Long, val state: String, val step: Int?,
                            val stability: Double, val difficulty: Double, val due: String, val retrievabilityIn5Days: Double)

    private val t0 = Instant.parse("2026-01-01T09:00:00Z")

    private fun run(steps: List<Step>) {
        val s = FsrsScheduler(enableFuzzing = false)
        var card = FsrsCard()
        for ((i, e) in steps.withIndex()) {
            val now = t0.plusSeconds(e.seconds)
            card = s.review(card, Rating.entries[e.rating - 1], now)
            val at = "step $i"
            assertEquals(at, e.state, card.state.name)
            assertEquals(at, e.step, card.step)
            assertEquals(at, e.stability, card.stability!!, 1e-9)
            assertEquals(at, e.difficulty, card.difficulty!!, 1e-9)
            assertEquals(at, Instant.parse(e.due), card.due)
            assertEquals(at, e.retrievabilityIn5Days, s.retrievability(card, now.plusSeconds(5 * 86400)), 1e-9)
        }
    }

    @Test fun goodPath() = run(listOf(
        Step(3, 0L, "Learning", 1, 2.3065, 2.118103970459016, "2026-01-01T09:10:00Z", 0.8388614268145294),
        Step(3, 60L, "Review", null, 2.3065, 2.111214235785395, "2026-01-03T09:01:00Z", 0.8388614268145294),
        Step(3, 660L, "Review", null, 2.3065, 2.1043313908464483, "2026-01-03T09:11:00Z", 0.8388614268145294),
        Step(3, 259200L, "Review", null, 10.977757474408312, 2.0974554287524403, "2026-01-15T09:00:00Z", 0.9446658293274226),
        Step(3, 1036800L, "Review", null, 41.52840790319967, 2.0905863426205262, "2026-02-24T09:00:00Z", 0.9829428839646309),
    ))

    @Test fun lapseAndRelearn() = run(listOf(
        Step(4, 0L, "Review", null, 8.2956, 1.0, "2026-01-09T09:00:00Z", 0.9309092928406029),
        Step(3, 777600L, "Review", null, 41.57871905908578, 1.0, "2026-02-21T09:00:00Z", 0.9829622476257135),
        Step(1, 1728000L, "Relearning", 0, 2.6417479258930316, 7.0269895692968385, "2026-01-21T09:10:00Z", 0.8506170502378358),
        Step(3, 1728600L, "Review", null, 2.6417479258930316, 7.0151909490243805, "2026-01-24T09:10:00Z", 0.8506170502378358),
        Step(2, 2073600L, "Review", null, 5.814691808167587, 8.003773029157445, "2026-01-31T09:00:00Z", 0.9100312851024163),
    ))

    @Test fun hardStartAndAgainInLearning() = run(listOf(
        Step(2, 0L, "Learning", 0, 1.2931, 5.112170705601056, "2026-01-01T09:05:30Z", 0.7853851990363399),
        Step(2, 330L, "Learning", 0, 1.2931, 6.7404595108297, "2026-01-01T09:11:00Z", 0.7853851990363399),
        Step(3, 1000L, "Learning", 1, 1.3358997622047517, 6.728947420615708, "2026-01-01T09:26:40Z", 0.7885010632770655),
        Step(1, 172800L, "Learning", 0, 0.3770658118012804, 8.91005562961076, "2026-01-03T09:01:00Z", 0.6656847659360648),
        Step(1, 173400L, "Learning", 0, 0.14274685515198962, 9.626971066371613, "2026-01-03T09:11:00Z", 0.5771117029451149),
        Step(3, 174100L, "Learning", 1, 0.17048384197847083, 9.61257246460208, "2026-01-03T09:31:40Z", 0.5926305800296332),
    ))

    @Test fun easy() = run(listOf(
        Step(4, 0L, "Review", null, 8.2956, 1.0, "2026-01-09T09:00:00Z", 0.9309092928406029),
        Step(4, 1296000L, "Review", null, 95.50778429167745, 1.0, "2026-04-22T09:00:00Z", 0.9923121119324432),
        Step(4, 5184000L, "Review", null, 347.5448407869129, 1.0, "2027-02-13T09:00:00Z", 0.9978427052457641),
    ))

    @Test fun fuzzStaysNearTheInterval() {
        val card = FsrsCard(FsrsState.Review, null, 41.5, 2.0, t0, t0)
        val at = t0.plusSeconds(86400 * 40)
        val plain = daysBetween(at, FsrsScheduler(enableFuzzing = false).review(card, Rating.Good, at).due)
        val s = FsrsScheduler(random = kotlin.random.Random(1))
        val seen = (1..200).map { daysBetween(at, s.review(card, Rating.Good, at).due) }.toSet()
        assertTrue("fuzz should vary: $seen", seen.size > 3)
        assertTrue("$plain vs $seen", seen.all { it in (plain * 0.85).toLong()..(plain * 1.15).toLong() + 1 })
    }
}
