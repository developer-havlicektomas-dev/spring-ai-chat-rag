package dev.havlicek.rag

import io.micrometer.observation.Observation
import io.micrometer.observation.ObservationHandler
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.LoggerFactory
import org.springframework.ai.chat.observation.ChatModelObservationContext
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.HttpMethod
import org.springframework.http.server.PathContainer
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.servlet.HandlerInterceptor
import org.springframework.web.servlet.config.annotation.InterceptorRegistry
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer
import org.springframework.web.util.pattern.PathPatternParser
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.ceil
import kotlin.math.min

// ---------------------------------------------------------------------------------------------------------------
// F10 config: rag.rate-limit, rag.budget, rag.limits
// ---------------------------------------------------------------------------------------------------------------

/** Per-caller token bucket in front of the endpoints that call an LLM ([paths], POST only). */
@ConfigurationProperties("rag.rate-limit")
data class RateLimitProperties(
    val enabled: Boolean = true,
    /** burst size */
    val capacity: Long = 10,
    /** [refillTokens] come back every [refillPeriod] (continuously, not in one step) */
    val refillTokens: Long = 10,
    val refillPeriod: Duration = Duration.ofMinutes(1),
    val paths: List<String> = listOf(
        "/kotlin/chat/ask", "/kotlin/chat/stream", "/kotlin/chat/tools", "/kotlin/koog/**", "/kotlin/agent/**",
        "/kotlin/ops/**", "/kotlin/study/generate", "/kotlin/evals/**",
    ),
)

/**
 * Cost guardrails: per-user daily token + USD budget by plan, a global daily USD cap for the whole app and a kill
 * switch. Checked before every LLM endpoint call, recorded after every model call (prices = rag.eval.models).
 */
@ConfigurationProperties("rag.budget")
data class BudgetProperties(
    val enabled: Boolean = true,
    /** true = every LLM endpoint answers 503 LLM_DISABLED (incident, runaway bill) */
    val killSwitch: Boolean = false,
    /** all users together; reaching it pauses LLM endpoints until 00:00 UTC (503 SPEND_CAP_REACHED) */
    val globalDailyUsd: Double = 5.0,
    val free: PlanBudget = PlanBudget(200_000, 0.10),
    val pro: PlanBudget = PlanBudget(2_000_000, 2.00),
) {
    data class PlanBudget(val dailyTokens: Long, val dailyUsd: Double)

    fun of(plan: Plan) = when (plan) {
        Plan.FREE -> free
        Plan.PRO -> pro
    }
}

/** Request-size and answer-size caps. */
@ConfigurationProperties("rag.limits")
data class RequestLimitProperties(
    /** bigger JSON bodies on LLM endpoints -> 413 before anything is parsed (40k-char PRO notes are ~45-120 KB) */
    val maxBodyBytes: Long = 256 * 1024,
    /** max_completion_tokens for the /chat answer call (the global default is spring.ai.openai.chat.options) */
    val chatMaxCompletionTokens: Int = 800,
)

// ---------------------------------------------------------------------------------------------------------------
// Rate limiting
// ---------------------------------------------------------------------------------------------------------------

class RateLimitedException(val decision: RateLimitDecision, val policy: String) : ApiException(
    ErrorCode.RATE_LIMITED,
    "Too many requests, retry in ${decision.retryAfterSeconds} s",
    mapOf("limit" to decision.limit, "retryAfterSeconds" to decision.retryAfterSeconds),
    rateLimitHeaders(decision, policy) + ("Retry-After" to decision.retryAfterSeconds.toString()),
)

data class RateLimitDecision(val allowed: Boolean, val limit: Long, val remaining: Long, val retryAfterSeconds: Long, val resetSeconds: Long)

/** IETF draft-ietf-httpapi-ratelimit-headers: `RateLimit-Policy: "llm";q=10;w=60` + `RateLimit: "llm";r=7;t=12`. */
fun rateLimitHeaders(d: RateLimitDecision, policy: String): Map<String, String> = mapOf(
    "RateLimit-Policy" to policy,
    "RateLimit" to "\"llm\";r=${d.remaining};t=${d.resetSeconds}",
)

/**
 * F10: classic token bucket, in memory, one per key (user id). Lazy refill on access: tokens += elapsed * rate,
 * capped at capacity; a request costs 1 token. Single instance only - with N replicas, move the state to Redis or
 * Postgres (e.g. Bucket4j's JDBC/Redis backends).
 */
class TokenBucketRateLimiter(
    private val capacity: Long,
    refillTokens: Long,
    refillPeriod: Duration,
    private val clock: Clock = Clock.systemUTC(),
) {
    init {
        require(capacity > 0 && refillTokens > 0 && !refillPeriod.isZero && !refillPeriod.isNegative) { "rate limit needs capacity, refill-tokens and refill-period > 0" }
    }

    private val tokensPerMilli = refillTokens.toDouble() / refillPeriod.toMillis()
    private val buckets = ConcurrentHashMap<String, Bucket>()
    val policy = "\"llm\";q=$capacity;w=${refillPeriod.seconds * capacity / refillTokens}"

    private class Bucket(var tokens: Double, var updatedAt: Long)

    fun tryConsume(key: String): RateLimitDecision {
        if (buckets.size > MAX_KEYS) evictFull()
        val bucket = buckets.computeIfAbsent(key) { Bucket(capacity.toDouble(), clock.millis()) }
        synchronized(bucket) {
            refill(bucket)
            val allowed = bucket.tokens >= 1.0
            if (allowed) bucket.tokens -= 1.0
            val retryAfter = if (allowed) 0 else ceil((1.0 - bucket.tokens) / tokensPerMilli / 1000).toLong().coerceAtLeast(1)
            val reset = ceil((capacity - bucket.tokens) / tokensPerMilli / 1000).toLong()
            return RateLimitDecision(allowed, capacity, bucket.tokens.toLong(), retryAfter, reset)
        }
    }

    fun remaining(key: String): Long = buckets[key]?.let { synchronized(it) { refill(it); it.tokens.toLong() } } ?: capacity

    private fun refill(bucket: Bucket) {
        val now = clock.millis()
        bucket.tokens = min(capacity.toDouble(), bucket.tokens + (now - bucket.updatedAt) * tokensPerMilli)
        bucket.updatedAt = now
    }

    /** full buckets carry no information, so dropping them is free; bounds memory with many one-off callers */
    private fun evictFull() = buckets.entries.removeIf { (_, b) -> synchronized(b) { refill(b); b.tokens >= capacity } }

    private companion object {
        const val MAX_KEYS = 50_000
    }
}

// ---------------------------------------------------------------------------------------------------------------
// Cost guardrails: usage store + guard + observation handler
// ---------------------------------------------------------------------------------------------------------------

data class UsageTotals(val calls: Int = 0, val promptTokens: Long = 0, val completionTokens: Long = 0, val costUsd: BigDecimal = BigDecimal.ZERO, val unpricedCalls: Int = 0) {
    val totalTokens get() = promptTokens + completionTokens
}

/** One model call: who, when (UTC day), tokens and its price (null = model not in the price table). */
data class LlmCall(val userId: UUID, val day: LocalDate, val model: String?, val promptTokens: Int, val completionTokens: Int, val costUsd: BigDecimal?)

/** F10: per-user, per-day LLM usage (V5__llm_usage.sql) or a map (memory profile / tests). */
interface LlmUsageStore {
    fun record(call: LlmCall)
    fun totals(userId: UUID, day: LocalDate): UsageTotals
    fun globalCostUsd(day: LocalDate): BigDecimal
}

class JdbcLlmUsageStore(private val jdbc: JdbcClient) : LlmUsageStore {
    /** one atomic upsert per model call, so parallel calls (study windows, agent tool loops) never lose an increment */
    override fun record(call: LlmCall) {
        jdbc.sql(
            """
            INSERT INTO llm_usage (user_id, usage_day, calls, prompt_tokens, completion_tokens, cost_usd, unpriced_calls)
            VALUES (:userId, :day, 1, :prompt, :completion, :cost, :unpriced)
            ON CONFLICT (user_id, usage_day) DO UPDATE SET
                calls = llm_usage.calls + 1,
                prompt_tokens = llm_usage.prompt_tokens + EXCLUDED.prompt_tokens,
                completion_tokens = llm_usage.completion_tokens + EXCLUDED.completion_tokens,
                cost_usd = llm_usage.cost_usd + EXCLUDED.cost_usd,
                unpriced_calls = llm_usage.unpriced_calls + EXCLUDED.unpriced_calls,
                updated_at = now()
            """.trimIndent()
        )
            .param("userId", call.userId).param("day", call.day)
            .param("prompt", call.promptTokens.toLong()).param("completion", call.completionTokens.toLong())
            .param("cost", call.costUsd ?: BigDecimal.ZERO).param("unpriced", if (call.costUsd == null) 1 else 0)
            .update()
    }

    override fun totals(userId: UUID, day: LocalDate): UsageTotals =
        jdbc.sql("SELECT calls, prompt_tokens, completion_tokens, cost_usd, unpriced_calls FROM llm_usage WHERE user_id = :userId AND usage_day = :day")
            .param("userId", userId).param("day", day)
            .query { rs, _ -> UsageTotals(rs.getInt(1), rs.getLong(2), rs.getLong(3), rs.getBigDecimal(4), rs.getInt(5)) }
            .optional().orElse(UsageTotals())

    override fun globalCostUsd(day: LocalDate): BigDecimal =
        jdbc.sql("SELECT COALESCE(SUM(cost_usd), 0) FROM llm_usage WHERE usage_day = :day").param("day", day)
            .query(BigDecimal::class.java).single()
}

class InMemoryLlmUsageStore : LlmUsageStore {
    private val totals = ConcurrentHashMap<Pair<UUID, LocalDate>, UsageTotals>()

    override fun record(call: LlmCall) {
        totals.merge(
            call.userId to call.day,
            UsageTotals(1, call.promptTokens.toLong(), call.completionTokens.toLong(), call.costUsd ?: BigDecimal.ZERO, if (call.costUsd == null) 1 else 0),
        ) { a, b -> UsageTotals(a.calls + b.calls, a.promptTokens + b.promptTokens, a.completionTokens + b.completionTokens, a.costUsd + b.costUsd, a.unpricedCalls + b.unpricedCalls) }
    }

    override fun totals(userId: UUID, day: LocalDate) = totals[userId to day] ?: UsageTotals()

    override fun globalCostUsd(day: LocalDate): BigDecimal =
        totals.filterKeys { it.second == day }.values.fold(BigDecimal.ZERO) { acc, t -> acc + t.costUsd }
}

class BudgetExceededException(plan: Plan, used: UsageTotals, budget: BudgetProperties.PlanBudget, resetsAt: Instant, retryAfterSeconds: Long) : ApiException(
    ErrorCode.BUDGET_EXCEEDED,
    "Daily LLM budget of plan ${plan.wire} used up (${used.totalTokens}/${budget.dailyTokens} tokens, \$${used.costUsd.setScale(4, RoundingMode.HALF_UP)}/\$${budget.dailyUsd}); resets at $resetsAt",
    mapOf(
        "plan" to plan, "usedTokens" to used.totalTokens, "limitTokens" to budget.dailyTokens,
        "usedUsd" to used.costUsd.setScale(6, RoundingMode.HALF_UP), "limitUsd" to budget.dailyUsd, "resetsAt" to resetsAt,
        "upgradeTo" to if (plan == Plan.FREE) Plan.PRO else null,
    ),
    mapOf("Retry-After" to retryAfterSeconds.toString()),
)

data class BudgetView(
    val userId: String,
    val plan: Plan,
    val day: LocalDate,
    val resetsAt: Instant,
    val calls: Int,
    val promptTokens: Long,
    val completionTokens: Long,
    val totalTokens: Long,
    val dailyTokens: Long,
    val remainingTokens: Long,
    val costUsd: BigDecimal,
    val dailyUsd: Double,
    val remainingUsd: BigDecimal,
    /** calls with a model that has no price in rag.eval.models (counted in tokens, not in USD) */
    val unpricedCalls: Int,
    val llmEnabled: Boolean,
    val rateLimit: RateLimitView?,
)

data class RateLimitView(val capacity: Long, val remaining: Long, val refillTokens: Long, val refillPeriod: Duration)

/**
 * F10: before an LLM endpoint runs -> kill switch, global daily cap, the caller's daily budget (checks the totals so
 * far, so one in-flight call can overshoot by at most its own size - bounded by max_completion_tokens).
 * After every model call -> [record] (from [LlmUsageObservationHandler]). Days are UTC like the F9 study quota.
 */
class CostGuard(
    private val users: UserDirectory,
    private val store: LlmUsageStore,
    private val models: ModelCatalog,
    private val props: BudgetProperties,
    private val clock: Clock = Clock.systemUTC(),
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val userIds = ConcurrentHashMap<String, UUID>()

    fun checkBefore(externalUserId: String) {
        checkGlobal()
        checkUser(externalUserId)
    }

    fun checkGlobal() {
        if (props.killSwitch) throw ApiException(ErrorCode.LLM_DISABLED, "LLM features are switched off by the operator", headers = mapOf("Retry-After" to "300"))
        if (!props.enabled) return
        val day = today()
        if (store.globalCostUsd(day) >= BigDecimal.valueOf(props.globalDailyUsd)) {
            log.warn("Global daily LLM spend cap of \${} reached", props.globalDailyUsd)
            throw ApiException(ErrorCode.SPEND_CAP_REACHED, "Today's LLM spend cap is reached; LLM features resume at ${resetsAt(day)}",
                mapOf("resetsAt" to resetsAt(day)), mapOf("Retry-After" to retryAfter(day).toString()))
        }
    }

    fun checkUser(externalUserId: String) {
        if (!props.enabled) return
        val day = today()
        val user = users.touch(externalUserId)
        userIds[externalUserId] = user.id
        val plan = Plan.of(user.plan)
        val budget = props.of(plan)
        val used = store.totals(user.id, day)
        if (used.totalTokens >= budget.dailyTokens || used.costUsd >= BigDecimal.valueOf(budget.dailyUsd)) {
            throw BudgetExceededException(plan, used, budget, resetsAt(day), retryAfter(day))
        }
    }

    /** Records one model call; never throws (a bookkeeping failure must not fail the user's request). */
    fun record(externalUserId: String, model: String?, promptTokens: Int, completionTokens: Int) {
        runCatching {
            val id = userIds[externalUserId] ?: users.touch(externalUserId).id.also { userIds[externalUserId] = it }
            val cost = model?.let { models.costUsdResolved(it, promptTokens, completionTokens) }?.let { BigDecimal.valueOf(it) }
            store.record(LlmCall(id, today(), model, promptTokens, completionTokens, cost))
        }.onFailure { log.warn("Could not record LLM usage for {}", externalUserId, it) }
    }

    fun view(externalUserId: String, rateLimit: RateLimitView?): BudgetView {
        val user = users.touch(externalUserId)
        val plan = Plan.of(user.plan)
        val budget = props.of(plan)
        val day = today()
        val used = store.totals(user.id, day)
        return BudgetView(
            user.externalId, plan, day, resetsAt(day), used.calls, used.promptTokens, used.completionTokens, used.totalTokens,
            budget.dailyTokens, (budget.dailyTokens - used.totalTokens).coerceAtLeast(0),
            used.costUsd.setScale(6, RoundingMode.HALF_UP), budget.dailyUsd,
            (BigDecimal.valueOf(budget.dailyUsd) - used.costUsd).max(BigDecimal.ZERO).setScale(6, RoundingMode.HALF_UP),
            used.unpricedCalls, !props.killSwitch, rateLimit,
        )
    }

    private fun today(): LocalDate = LocalDate.now(clock.withZone(ZoneOffset.UTC))
    private fun resetsAt(day: LocalDate): Instant = day.plusDays(1).atStartOfDay().toInstant(ZoneOffset.UTC)
    private fun retryAfter(day: LocalDate): Long = maxOf(1, Duration.between(clock.instant(), resetsAt(day)).seconds)

    companion object {
        /** LLM calls outside any request (startup evals, schedulers) are booked on this user. */
        const val SYSTEM_USER = "system"
    }
}

/**
 * F10: books EVERY chat model call - RAG query rewrite + answer, Koog agent turns, study windows in parallel - via
 * Spring AI's observation (the same one that feeds gen_ai.client.token.usage), attributed to the request's
 * [ApiCaller]. The principal reaches coroutine and Reactor threads through context propagation
 * (spring.reactor.context-propagation=auto + Spring Security's ThreadLocalAccessor).
 */
class LlmUsageObservationHandler(private val guard: CostGuard) : ObservationHandler<ChatModelObservationContext> {
    override fun supportsContext(context: Observation.Context) = context is ChatModelObservationContext

    override fun onStop(context: ChatModelObservationContext) {
        val response = context.response ?: return
        val usage = response.metadata.usage
        // the provider's dated snapshot ("gpt-4o-mini-2024-07-18"), else the requested model
        val model = response.metadata.model.takeIf { it.isNotBlank() } ?: context.request.options?.model
        guard.record(currentCallerId() ?: CostGuard.SYSTEM_USER, model, usage.promptTokens, usage.completionTokens)
    }
}

// ---------------------------------------------------------------------------------------------------------------
// The interceptor in front of LLM endpoints + GET /kotlin/usage
// ---------------------------------------------------------------------------------------------------------------

/**
 * F10: runs after authentication, before the controller (so its exceptions reach [ApiExceptionHandler]):
 * body size cap (413) -> kill switch / spend cap (503) -> rate limit (429 RATE_LIMITED) -> daily budget
 * (429 BUDGET_EXCEEDED). Successful calls get the RateLimit headers too.
 */
class LlmGuardInterceptor(
    private val rateLimiter: TokenBucketRateLimiter?,
    private val costGuard: CostGuard,
    private val limits: RequestLimitProperties,
    private val paths: List<String>,
    private val defaultUser: String,
) : HandlerInterceptor {
    private val patterns = paths.map(PathPatternParser.defaultInstance::parse)

    fun applies(request: HttpServletRequest): Boolean {
        if (request.method != HttpMethod.POST.name()) return false
        val path = PathContainer.parsePath(request.requestURI.removePrefix(request.contextPath))
        return patterns.any { it.matches(path) }
    }

    override fun preHandle(request: HttpServletRequest, response: HttpServletResponse, handler: Any): Boolean {
        if (request.dispatcherType != jakarta.servlet.DispatcherType.REQUEST || !applies(request)) return true
        if (request.contentLengthLong > limits.maxBodyBytes) {
            throw ApiException(ErrorCode.PAYLOAD_TOO_LARGE, "Request body is ${request.contentLengthLong} bytes, the limit is ${limits.maxBodyBytes}",
                mapOf("limit" to limits.maxBodyBytes))
        }
        val caller = currentCallerId() ?: defaultUser
        costGuard.checkGlobal() // kill switch + global cap first: no point spending a rate-limit token
        rateLimiter?.let { limiter ->
            val decision = limiter.tryConsume(caller)
            if (!decision.allowed) throw RateLimitedException(decision, limiter.policy)
            rateLimitHeaders(decision, limiter.policy).forEach(response::setHeader)
        }
        costGuard.checkUser(caller)
        return true
    }
}

@RestController
@RequestMapping("/kotlin")
class UsageController(
    private val costGuard: CostGuard,
    private val rateLimiter: TokenBucketRateLimiter?,
    private val rateLimit: RateLimitProperties,
    private val rag: RagProperties,
) {
    /** F10: today's LLM usage of the caller vs. the plan's daily budget, and the rate-limit bucket. */
    @GetMapping("/usage")
    fun usage(@CallerId user: String?): BudgetView {
        val id = UserDirectory.requireValidId(user ?: rag.defaultUser)
        return costGuard.view(id, rateLimiter?.let { RateLimitView(rateLimit.capacity, it.remaining(id), rateLimit.refillTokens, rateLimit.refillPeriod) })
    }
}

@Configuration
@EnableConfigurationProperties(RateLimitProperties::class, BudgetProperties::class, RequestLimitProperties::class)
class GuardrailsConfig {
    @Bean
    fun rateLimiter(props: RateLimitProperties): TokenBucketRateLimiter? =
        if (props.enabled) TokenBucketRateLimiter(props.capacity, props.refillTokens, props.refillPeriod) else null

    @Bean
    fun costGuard(users: UserDirectory, store: LlmUsageStore, models: ModelCatalog, props: BudgetProperties) = CostGuard(users, store, models, props)

    @Bean
    fun llmUsageObservationHandler(costGuard: CostGuard) = LlmUsageObservationHandler(costGuard)

    /** A @Bean (not a WebMvcConfigurer class), so @WebMvcTest slices don't pull in the guard's dependencies. */
    @Bean
    fun llmGuardWebConfigurer(
        rateLimiter: TokenBucketRateLimiter?, costGuard: CostGuard, limits: RequestLimitProperties, rateLimit: RateLimitProperties, rag: RagProperties,
    ): WebMvcConfigurer = object : WebMvcConfigurer {
        override fun addInterceptors(registry: InterceptorRegistry) {
            registry.addInterceptor(LlmGuardInterceptor(rateLimiter, costGuard, limits, rateLimit.paths, rag.defaultUser))
        }
    }
}
