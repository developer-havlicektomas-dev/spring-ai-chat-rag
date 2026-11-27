package dev.havlicek.rag

import org.hamcrest.Matchers.containsString
import org.hamcrest.Matchers.startsWith
import org.junit.jupiter.api.Test
import org.mockito.BDDMockito.given
import org.mockito.Mockito.verifyNoInteractions
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.test.context.TestPropertySource
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post

/** F10: the LLM guard interceptor over HTTP: RateLimit headers, 429 + Retry-After, budget 429, 413, GET /kotlin/usage. */
@WebMvcTest(controllers = [KotlinController::class, UsageController::class])
@DevSecurity
@Import(GuardrailsConfig::class, GuardrailsWebTest.Beans::class)
@TestPropertySource(
    properties = [
        "rag.rate-limit.capacity=2", "rag.rate-limit.refill-tokens=1", "rag.rate-limit.refill-period=1h",
        "rag.budget.free.daily-tokens=1000", "rag.limits.max-body-bytes=512",
    ]
)
class GuardrailsWebTest(@Autowired private val mvc: MockMvc, @Autowired private val costGuard: CostGuard) {
    @TestConfiguration
    class Beans {
        @Bean fun userDirectory(): UserDirectory = InMemoryUserDirectory()
        @Bean fun llmUsageStore(): LlmUsageStore = InMemoryLlmUsageStore()
        @Bean fun modelCatalog() = ModelCatalog(listOf(EvalModel("gpt-4o-mini", 0.15, 0.60)))
    }

    @MockitoBean lateinit var docsService: DocsService
    @MockitoBean lateinit var chatService: ChatService
    @MockitoBean lateinit var koogBridgeService: KoogBridgeService
    @MockitoBean lateinit var koogAgentService: KoogAgentService
    @MockitoBean lateinit var koogStudyService: KoogStudyService
    @MockitoBean lateinit var opsAgentService: OpsAgentService
    @MockitoBean lateinit var ragEvalRunner: RagEvalRunner

    private fun ask(user: String, body: String = """{"question":"q?"}""") = mvc.post("/kotlin/chat/ask") {
        contentType = MediaType.APPLICATION_JSON
        header(USER_HEADER, user)
        content = body
    }

    @Test
    fun `burst over the bucket is 429 RATE_LIMITED with Retry-After and RateLimit headers`() {
        given(chatService.ask("q?", AskOptions(userId = "burst"))).willReturn(RagAnswer("a", emptyList(), "c-1"))
        ask("burst").andExpect {
            status { isOk() }
            header { string("RateLimit-Policy", "\"llm\";q=2;w=7200") }
            header { string("RateLimit", startsWith("\"llm\";r=1;")) }
        }
        ask("burst").andExpect { status { isOk() } }
        ask("burst").andExpect {
            status { isTooManyRequests() }
            content { contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON) }
            header { string("Retry-After", "3600") }
            header { string("RateLimit", startsWith("\"llm\";r=0;")) }
            jsonPath("$.code") { value("RATE_LIMITED") }
            jsonPath("$.retryAfterSeconds") { value(3600) }
        }
        // GETs that don't call an LLM are not limited, and the bucket is per caller
        mvc.get("/kotlin/chat/sessions") { header(USER_HEADER, "burst") }.andExpect { status { isOk() } }
        given(chatService.ask("q?", AskOptions(userId = "someone-else"))).willReturn(RagAnswer("a", emptyList(), "c-2"))
        ask("someone-else").andExpect { status { isOk() } }
    }

    @Test
    fun `used-up daily budget is 429 BUDGET_EXCEEDED before the controller runs, usage endpoint shows it`() {
        costGuard.record("spender", "gpt-4o-mini-2024-07-18", 900, 150)
        ask("spender").andExpect {
            status { isTooManyRequests() }
            header { exists("Retry-After") }
            jsonPath("$.code") { value("BUDGET_EXCEEDED") }
            jsonPath("$.usedTokens") { value(1050) }
            jsonPath("$.limitTokens") { value(1000) }
            jsonPath("$.upgradeTo") { value("pro") }
        }
        verifyNoInteractions(chatService)
        mvc.get("/kotlin/usage") { header(USER_HEADER, "spender") }.andExpect {
            status { isOk() }
            jsonPath("$.userId") { value("spender") }
            jsonPath("$.totalTokens") { value(1050) }
            jsonPath("$.remainingTokens") { value(0) }
            jsonPath("$.costUsd") { value(0.000225) }
            jsonPath("$.rateLimit.capacity") { value(2) }
        }
    }

    @Test
    fun `oversized body is 413 and invalid dev user id is 400`() {
        ask("big", """{"question":"${"x".repeat(600)}"}""").andExpect {
            status { isPayloadTooLarge() }
            jsonPath("$.code") { value("PAYLOAD_TOO_LARGE") }
        }
        ask("bad user!").andExpect {
            status { isBadRequest() }
            jsonPath("$.detail") { value(containsString("X-User-Id")) }
        }
    }
}
