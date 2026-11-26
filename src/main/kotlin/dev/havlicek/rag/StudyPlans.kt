package dev.havlicek.rag

import com.fasterxml.jackson.annotation.JsonValue
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.jdbc.core.simple.JdbcClient
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** F9: subscription plans; [wire] is what app_user.plan stores (V4 check constraint). */
enum class Plan(@get:JsonValue val wire: String) {
    FREE("free"), PRO("pro");

    companion object {
        fun of(value: String): Plan = entries.firstOrNull { it.wire.equals(value, ignoreCase = true) }
            ?: throw IllegalArgumentException("Unknown plan '$value', expected one of ${entries.map { it.wire }}")
    }
}

/** What a plan may do per generation and per day. */
data class PlanLimits(
    val features: Set<StudyFeature>,
    val difficulties: Set<Difficulty>,
    val maxQuizSize: Int,
    val maxNoteChars: Int,
    val dailyGenerations: Int,
)

/** F9: rag.study.* (application.yml). Limits live in config, so pricing experiments need no code change. */
@ConfigurationProperties("rag.study")
data class StudyProperties(
    val free: PlanLimits = PlanLimits(
        features = setOf(StudyFeature.EXPLANATION, StudyFeature.QUIZ),
        difficulties = setOf(Difficulty.EASY, Difficulty.MEDIUM),
        maxQuizSize = 5, maxNoteChars = 6_000, dailyGenerations = 3,
    ),
    val pro: PlanLimits = PlanLimits(
        features = StudyFeature.entries.toSet(),
        difficulties = Difficulty.entries.toSet(),
        maxQuizSize = 15, maxNoteChars = 40_000, dailyGenerations = 50,
    ),
    /** chunk size for citations / quote offsets (characters, paragraph + sentence aware) */
    val chunkChars: Int = 1_200,
    /** max note characters per LLM call; longer notes are split into windows generated in parallel */
    val windowChars: Int = 12_000,
    val temperature: Double = 0.2,
    /** DEV ONLY: PUT /kotlin/study/plan lets a caller switch its own plan (stand-in for billing). Off by default. */
    val devPlanSwitch: Boolean = false,
) {
    fun limits(plan: Plan) = when (plan) {
        Plan.FREE -> free
        Plan.PRO -> pro
    }
}

/** 402: the plan does not include this (feature, quiz size, difficulty, note length). */
class PlanLimitException(
    val reason: String,
    val plan: Plan,
    val limit: Any,
    val requested: Any,
    message: String,
) : RuntimeException(message)

/** 429: daily generations used up; resets at [resetsAt]. */
class QuotaExceededException(val plan: Plan, val used: Int, val limit: Int, val resetsAt: Instant) :
    RuntimeException("Daily Study Guide quota of $limit generations used up (plan ${plan.wire}); resets at $resetsAt")

data class StudyUsage(val plan: Plan, val usedToday: Int, val dailyLimit: Int, val resetsAt: Instant, val limits: PlanLimits)

/** One generation for the study_generation log. */
data class GenerationRecord(
    val userId: UUID,
    val plan: Plan,
    val features: Set<StudyFeature>,
    val noteChars: Int,
    val chunks: Int,
    val quizSize: Int,
    val answerType: String,
    val model: String?,
    val inputTokens: Int?,
    val outputTokens: Int?,
    val latencyMs: Long,
)

/** F9: per-user, per-day generation counter (+ generation log). */
interface StudyUsageStore {
    /** Atomically takes one generation if fewer than [limit] were used on [day]; returns the new count or null. */
    fun tryConsume(userId: UUID, day: LocalDate, limit: Int): Int?

    /** Gives a generation back (the LLM call failed, so the user should not pay for it). */
    fun refund(userId: UUID, day: LocalDate)

    fun used(userId: UUID, day: LocalDate): Int

    fun record(generation: GenerationRecord)
}

class JdbcStudyUsageStore(private val jdbc: JdbcClient) : StudyUsageStore {
    override fun tryConsume(userId: UUID, day: LocalDate, limit: Int): Int? =
        if (limit <= 0) null else jdbc.sql(
            """
            INSERT INTO study_usage (user_id, usage_day, generations) VALUES (:userId, :day, 1)
            ON CONFLICT (user_id, usage_day) DO UPDATE SET generations = study_usage.generations + 1
            WHERE study_usage.generations < :limit
            RETURNING generations
            """.trimIndent()
        ).param("userId", userId).param("day", day).param("limit", limit)
            .query(Int::class.java).optional().orElse(null)

    override fun refund(userId: UUID, day: LocalDate) {
        jdbc.sql("UPDATE study_usage SET generations = generations - 1 WHERE user_id = :userId AND usage_day = :day AND generations > 0")
            .param("userId", userId).param("day", day).update()
    }

    override fun used(userId: UUID, day: LocalDate): Int =
        jdbc.sql("SELECT generations FROM study_usage WHERE user_id = :userId AND usage_day = :day")
            .param("userId", userId).param("day", day).query(Int::class.java).optional().orElse(0)

    override fun record(generation: GenerationRecord) {
        with(generation) {
            jdbc.sql(
                """
                INSERT INTO study_generation (user_id, plan, features, note_chars, chunks, quiz_size, answer_type, model, input_tokens, output_tokens, latency_ms)
                VALUES (:userId, :plan, :features, :noteChars, :chunks, :quizSize, :answerType, :model, :inputTokens, :outputTokens, :latencyMs)
                """.trimIndent()
            )
                .param("userId", userId).param("plan", plan.wire).param("features", features.sorted().joinToString(","))
                .param("noteChars", noteChars).param("chunks", chunks).param("quizSize", quizSize).param("answerType", answerType)
                .param("model", model).param("inputTokens", inputTokens).param("outputTokens", outputTokens).param("latencyMs", latencyMs)
                .update()
        }
    }
}

class InMemoryStudyUsageStore : StudyUsageStore {
    private val counts = ConcurrentHashMap<Pair<UUID, LocalDate>, Int>()
    val generations: MutableList<GenerationRecord> = java.util.Collections.synchronizedList(mutableListOf())

    override fun tryConsume(userId: UUID, day: LocalDate, limit: Int): Int? {
        var taken: Int? = null
        counts.compute(userId to day) { _, old ->
            val used = old ?: 0
            if (used < limit) (used + 1).also { taken = it } else used
        }
        return taken
    }

    override fun refund(userId: UUID, day: LocalDate) {
        counts.computeIfPresent(userId to day) { _, old -> (old - 1).coerceAtLeast(0) }
    }

    override fun used(userId: UUID, day: LocalDate): Int = counts[userId to day] ?: 0

    override fun record(generation: GenerationRecord) {
        generations += generation
    }
}

/** A consumed generation slot; [release] gives it back if generation fails. */
class QuotaTicket(val user: AppUser, val plan: Plan, val used: Int, val limit: Int, val resetsAt: Instant, private val onRelease: () -> Unit) {
    fun release() = onRelease()
}

/**
 * F9: the paid-feature guard. Order matters: cheap plan checks first (402, no quota used), then the atomic daily
 * quota (429). Days are UTC so every server agrees on when the quota resets.
 */
class StudyGuard(
    private val users: UserDirectory,
    private val usage: StudyUsageStore,
    private val props: StudyProperties,
    private val clock: Clock = Clock.systemUTC(),
) {
    fun check(plan: Plan, request: StudyGenerateRequest) {
        val limits = props.limits(plan)
        request.features.firstOrNull { it !in limits.features }?.let {
            throw PlanLimitException("feature", plan, limits.features.sorted(), it, "Feature $it is not included in plan ${plan.wire}")
        }
        if (StudyFeature.QUIZ in request.features && request.quizSize > limits.maxQuizSize) {
            throw PlanLimitException("quizSize", plan, limits.maxQuizSize, request.quizSize,
                "Plan ${plan.wire} allows up to ${limits.maxQuizSize} quiz questions, requested ${request.quizSize}")
        }
        if (request.difficulty !in limits.difficulties) {
            throw PlanLimitException("difficulty", plan, limits.difficulties.sorted(), request.difficulty,
                "Difficulty ${request.difficulty} is not included in plan ${plan.wire}")
        }
        if (request.text.length > limits.maxNoteChars) {
            throw PlanLimitException("noteLength", plan, limits.maxNoteChars, request.text.length,
                "Plan ${plan.wire} accepts notes up to ${limits.maxNoteChars} characters, got ${request.text.length}")
        }
    }

    /** Plan check + takes one generation from today's quota. */
    fun authorize(externalUserId: String, request: StudyGenerateRequest): QuotaTicket {
        val user = users.touch(externalUserId)
        val plan = Plan.of(user.plan)
        check(plan, request)
        val limit = props.limits(plan).dailyGenerations
        val day = today()
        val used = usage.tryConsume(user.id, day, limit) ?: throw QuotaExceededException(plan, usage.used(user.id, day), limit, resetsAt(day))
        return QuotaTicket(user, plan, used, limit, resetsAt(day)) { usage.refund(user.id, day) }
    }

    fun usage(externalUserId: String): StudyUsage {
        val user = users.touch(externalUserId)
        val plan = Plan.of(user.plan)
        val day = today()
        return StudyUsage(plan, usage.used(user.id, day), props.limits(plan).dailyGenerations, resetsAt(day), props.limits(plan))
    }

    private fun today(): LocalDate = LocalDate.now(clock.withZone(ZoneOffset.UTC))
    private fun resetsAt(day: LocalDate): Instant = day.plusDays(1).atStartOfDay().toInstant(ZoneOffset.UTC)
}
