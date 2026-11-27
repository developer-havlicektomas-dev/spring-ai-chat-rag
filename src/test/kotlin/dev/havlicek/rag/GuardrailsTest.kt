package dev.havlicek.rag

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.ai.chat.messages.AssistantMessage
import org.springframework.ai.chat.metadata.ChatResponseMetadata
import org.springframework.ai.chat.metadata.DefaultUsage
import org.springframework.ai.chat.model.ChatResponse
import org.springframework.ai.chat.model.Generation
import org.springframework.ai.chat.observation.ChatModelObservationContext
import org.springframework.ai.chat.prompt.Prompt
import org.springframework.ai.observation.AiOperationMetadata
import org.springframework.security.core.context.SecurityContextHolder
import java.math.BigDecimal
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

/** Mutable test clock. */
class TestClock(var now: Instant = Instant.parse("2026-11-27T10:00:00Z")) : Clock() {
    override fun getZone(): ZoneId = ZoneOffset.UTC
    override fun withZone(zone: ZoneId): Clock = this
    override fun instant(): Instant = now
    fun advance(d: Duration) { now = now.plus(d) }
}

/** F10: token bucket, cost math, budgets, kill switch, global cap and the observation-based usage booking. */
class GuardrailsTest {
    private val clock = TestClock()

    @Test
    fun `token bucket allows a burst, then 429 with retry-after, then refills continuously`() {
        val limiter = TokenBucketRateLimiter(capacity = 3, refillTokens = 3, refillPeriod = Duration.ofMinutes(1), clock = clock)
        repeat(3) { assertTrue(limiter.tryConsume("alice").allowed) }
        val denied = limiter.tryConsume("alice")
        assertFalse(denied.allowed)
        assertEquals(20, denied.retryAfterSeconds) // 1 token per 20 s
        assertEquals(0, denied.remaining)
        assertTrue(limiter.tryConsume("bob").allowed) // per caller
        clock.advance(Duration.ofSeconds(20))
        assertTrue(limiter.tryConsume("alice").allowed)
        assertFalse(limiter.tryConsume("alice").allowed)
        assertEquals("\"llm\";q=3;w=60", limiter.policy)
    }

    private val catalog = ModelCatalog(listOf(EvalModel("gpt-4o-mini", 0.15, 0.60), EvalModel("gpt-4.1", 2.0, 8.0)))
    private val users = InMemoryUserDirectory()
    private val store = InMemoryLlmUsageStore()

    private fun guard(props: BudgetProperties = BudgetProperties(globalDailyUsd = 1.0, free = BudgetProperties.PlanBudget(10_000, 0.01))) =
        CostGuard(users, store, catalog, props, clock)

    @Test
    fun `dated snapshots resolve to the price table`() {
        assertEquals("gpt-4o-mini", catalog.resolve("gpt-4o-mini-2024-07-18"))
        assertEquals("gpt-4.1", catalog.resolve("gpt-4.1-2025-04-14"))
        assertEquals(null, catalog.resolve("gpt-4o-mini-tts"))
        assertEquals(0.00075, catalog.costUsdResolved("gpt-4o-mini-2024-07-18", 1000, 1000)!!, 1e-12)
    }

    @Test
    fun `daily token budget blocks the next call with 429 BUDGET_EXCEEDED until midnight UTC`() {
        val guard = guard()
        guard.checkBefore("alice")
        guard.record("alice", "gpt-4o-mini-2024-07-18", 9_000, 1_000)
        val e = assertThrows<BudgetExceededException> { guard.checkBefore("alice") }
        assertEquals(ErrorCode.BUDGET_EXCEEDED, e.code)
        assertEquals(10_000L, e.properties["usedTokens"])
        assertEquals(Instant.parse("2026-11-28T00:00:00Z"), e.properties["resetsAt"])
        assertEquals("50400", e.headers["Retry-After"]) // 14 h to midnight UTC
        guard.checkBefore("bob") // other users unaffected
        clock.advance(Duration.ofHours(14)) // next UTC day
        guard.checkBefore("alice")
    }

    @Test
    fun `usd budget, pro plan, unpriced models and the usage view`() {
        val guard = guard()
        guard.record("carol", "gpt-4.1", 2_000, 1_000) // 0.004 + 0.008 = 0.012 USD > 0.01
        assertThrows<BudgetExceededException> { guard.checkBefore("carol") }
        users.setPlan("carol", "pro")
        guard.checkBefore("carol") // pro budget is larger
        guard.record("carol", "some-local-model", 10, 10)
        val view = guard.view("carol", null)
        assertEquals(Plan.PRO, view.plan)
        assertEquals(2, view.calls)
        assertEquals(1, view.unpricedCalls)
        assertEquals(0, BigDecimal("0.012").compareTo(view.costUsd))
        assertEquals(3_020L, view.totalTokens)
    }

    @Test
    fun `kill switch and global spend cap are 503`() {
        assertEquals(ErrorCode.LLM_DISABLED, assertThrows<ApiException> { guard(BudgetProperties(killSwitch = true)).checkBefore("alice") }.code)
        val guard = guard(BudgetProperties(globalDailyUsd = 0.01, free = BudgetProperties.PlanBudget(1_000_000, 100.0)))
        guard.record("u1", "gpt-4.1", 3_000, 0)  // 0.006
        guard.record("u2", "gpt-4.1", 2_000, 0)  // 0.004 -> 0.010 total
        val e = assertThrows<ApiException> { guard.checkBefore("u3") }
        assertEquals(ErrorCode.SPEND_CAP_REACHED, e.code)
        assertTrue(e.headers.containsKey("Retry-After"))
    }

    @Test
    fun `observation handler books the call on the authenticated caller, else on system`() {
        val handler = LlmUsageObservationHandler(guard())
        fun observed(model: String) = ChatModelObservationContext.builder()
            .prompt(Prompt("hi"))
            .provider("openai")
            .build()
            .apply {
                setResponse(
                    ChatResponse(
                        listOf(Generation(AssistantMessage("hello"))),
                        ChatResponseMetadata.builder().model(model).usage(DefaultUsage(100, 20)).build(),
                    )
                )
            }
        try {
            SecurityContextHolder.getContext().authentication = ApiCallerAuthentication(ApiCaller("dave", ApiCaller.Via.API_KEY))
            handler.onStop(observed("gpt-4o-mini-2024-07-18"))
        } finally {
            SecurityContextHolder.clearContext()
        }
        handler.onStop(observed("gpt-4o-mini"))
        assertEquals(120L, guard().view("dave", null).totalTokens)
        assertEquals(1, guard().view(CostGuard.SYSTEM_USER, null).calls)
        assertEquals(0, BigDecimal("0.000027").compareTo(guard().view("dave", null).costUsd))
    }

    @Test
    fun `upstream errors are classified without leaking provider text`() {
        val timeout = com.openai.errors.OpenAIIoException("Request failed", java.net.SocketTimeoutException("timeout"))
        assertEquals(ErrorCode.LLM_TIMEOUT, UpstreamErrors.classify(RuntimeException("wrapped", timeout))?.code)
        assertEquals(ErrorCode.LLM_UNAVAILABLE, UpstreamErrors.classify(com.openai.errors.OpenAIIoException("connection reset"))?.code)
        val rateLimited = com.openai.errors.RateLimitException.builder().headers(com.openai.core.http.Headers.builder().build()).build()
        assertEquals(ErrorCode.LLM_RATE_LIMITED, UpstreamErrors.classify(IllegalStateException(rateLimited))?.code)
        val server = com.openai.errors.InternalServerException.builder().statusCode(502).headers(com.openai.core.http.Headers.builder().build()).build()
        assertEquals(ErrorCode.LLM_UNAVAILABLE, UpstreamErrors.classify(server)?.code)
        assertEquals(null, UpstreamErrors.classify(IllegalStateException("bug")))
        assertTrue(UpstreamErrors.safeMessage(IllegalStateException("secret sk-123")).startsWith("INTERNAL_ERROR"))
        assertFalse(UpstreamErrors.safeMessage(timeout).contains("Request failed"))
        assertTrue(UpstreamErrors.safeMessage(timeout).startsWith("LLM_TIMEOUT"))
    }
}
