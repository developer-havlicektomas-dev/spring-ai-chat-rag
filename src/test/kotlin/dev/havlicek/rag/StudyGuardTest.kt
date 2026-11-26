package dev.havlicek.rag

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import kotlin.test.assertEquals

/** F9: paid-feature guard: plan limits (402) before the atomic daily quota (429). */
class StudyGuardTest {
    private class MutableClock(var now: Instant) : Clock() {
        override fun getZone(): ZoneId = ZoneOffset.UTC
        override fun withZone(zone: ZoneId): Clock = this
        override fun instant(): Instant = now
    }

    private val clock = MutableClock(Instant.parse("2026-11-26T22:00:00Z"))
    private val users = InMemoryUserDirectory()
    private val usage = InMemoryStudyUsageStore()
    private val guard = StudyGuard(users, usage, StudyProperties(), clock)
    private fun req(
        features: Set<StudyFeature> = setOf(StudyFeature.EXPLANATION, StudyFeature.QUIZ),
        quizSize: Int = 5, difficulty: Difficulty = Difficulty.MEDIUM, text: String = "note",
    ) = StudyGenerateRequest(text = text, quizSize = quizSize, difficulty = difficulty, features = features)

    @Test
    fun `free plan blocks pro features with the limit in the exception`() {
        val flash = assertThrows<PlanLimitException> { guard.authorize("free-1", req(features = setOf(StudyFeature.FLASHCARDS))) }
        assertEquals("feature", flash.reason)
        assertEquals("quizSize", assertThrows<PlanLimitException> { guard.authorize("free-1", req(quizSize = 6)) }.reason)
        assertEquals(5, assertThrows<PlanLimitException> { guard.authorize("free-1", req(quizSize = 6)) }.limit)
        assertEquals("difficulty", assertThrows<PlanLimitException> { guard.authorize("free-1", req(difficulty = Difficulty.HARD)) }.reason)
        assertEquals("noteLength", assertThrows<PlanLimitException> { guard.authorize("free-1", req(text = "x".repeat(6_001))) }.reason)
        assertEquals(0, guard.usage("free-1").usedToday) // rejected requests use no quota
    }

    @Test
    fun `quiz size only matters when a quiz is requested`() {
        guard.authorize("free-2", req(features = setOf(StudyFeature.EXPLANATION), quizSize = 50))
    }

    @Test
    fun `pro plan unlocks flashcards, hard quizzes and bigger quizzes`() {
        users.setPlan("pro-1", "pro")
        val ticket = guard.authorize("pro-1", req(features = StudyFeature.entries.toSet(), quizSize = 15, difficulty = Difficulty.HARD))
        assertEquals(Plan.PRO, ticket.plan)
        assertEquals(50, ticket.limit)
        assertThrows<PlanLimitException> { guard.authorize("pro-1", req(quizSize = 16)) }
    }

    @Test
    fun `daily quota is enforced, refundable and resets at UTC midnight`() {
        repeat(3) { assertEquals(it + 1, guard.authorize("free-3", req()).used) }
        val e = assertThrows<QuotaExceededException> { guard.authorize("free-3", req()) }
        assertEquals(3, e.used)
        assertEquals(Instant.parse("2026-11-27T00:00:00Z"), e.resetsAt)

        guard.usage("free-3").let { assertEquals(3, it.usedToday); assertEquals(3, it.dailyLimit) }
        usage.refund(users.find("free-3")!!.id, java.time.LocalDate.of(2026, 11, 26))
        guard.authorize("free-3", req()).release() // ticket released = generation failed
        assertEquals(2, guard.usage("free-3").usedToday)

        clock.now = Instant.parse("2026-11-27T00:00:01Z")
        assertEquals(1, guard.authorize("free-3", req()).used)
    }
}
