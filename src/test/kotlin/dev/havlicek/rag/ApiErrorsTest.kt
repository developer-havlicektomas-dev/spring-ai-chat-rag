package dev.havlicek.rag

import com.openai.core.http.Headers
import com.openai.errors.InternalServerException
import com.openai.errors.OpenAIIoException
import com.openai.errors.RateLimitException
import org.hamcrest.Matchers.not
import org.hamcrest.Matchers.containsString
import org.junit.jupiter.api.Test
import org.mockito.BDDMockito.given
import org.springframework.ai.chat.client.ChatClient
import org.springframework.ai.chat.model.ChatModel
import org.springframework.ai.chat.model.ChatResponse
import org.springframework.ai.chat.prompt.Prompt
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.core.io.ClassPathResource
import org.springframework.http.MediaType
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.header
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.net.SocketTimeoutException
import java.util.concurrent.atomic.AtomicReference

/**
 * F10: RFC 9457 shapes from the one @RestControllerAdvice, and upstream LLM failures (scripted failing ChatModel
 * behind the REAL Study Guide service) mapped to 503/504 with a safe message - no provider text, no stack trace.
 */
@WebMvcTest(controllers = [KotlinController::class, StudyController::class])
@DevSecurity
@Import(ApiErrorsTest.Beans::class)
class ApiErrorsTest(@Autowired private val mvc: MockMvc, @Autowired private val usage: StudyUsageStore, @Autowired private val users: UserDirectory) {

    @TestConfiguration
    @EnableConfigurationProperties(StudyProperties::class, RagProperties::class)
    class Beans {
        @Bean fun failure() = AtomicReference<RuntimeException>()

        @Bean fun userDirectory(): UserDirectory = InMemoryUserDirectory()
        @Bean fun studyUsageStore(): StudyUsageStore = InMemoryStudyUsageStore()
        @Bean fun studyGuard(users: UserDirectory, usage: StudyUsageStore, props: StudyProperties) = StudyGuard(users, usage, props)
        @Bean fun studyGuideService(guard: StudyGuard, usage: StudyUsageStore, props: StudyProperties, failure: AtomicReference<RuntimeException>): StudyGuideService {
            val failing = object : ChatModel {
                override fun call(prompt: Prompt): ChatResponse = throw failure.get()
            }
            return StudyGuideService(
                ChatClient.builder(failing), guard, usage, props,
                ClassPathResource("prompts/study-system.st"), ClassPathResource("prompts/study-generate.st"), ClassPathResource("prompts/study-fallback.st"),
            )
        }
    }

    @Autowired lateinit var failure: AtomicReference<RuntimeException>

    @MockitoBean lateinit var docsService: DocsService
    @MockitoBean lateinit var chatService: ChatService
    @MockitoBean lateinit var koogBridgeService: KoogBridgeService
    @MockitoBean lateinit var koogAgentService: KoogAgentService
    @MockitoBean lateinit var koogStudyService: KoogStudyService
    @MockitoBean lateinit var opsAgentService: OpsAgentService
    @MockitoBean lateinit var ragEvalRunner: RagEvalRunner

    private val note = "Photosynthesis happens in the chloroplasts of plant cells. The Calvin cycle runs in the stroma."

    private fun generate(user: String) = mvc.post("/kotlin/study/generate") {
        contentType = MediaType.APPLICATION_JSON
        header(USER_HEADER, user)
        content = """{"text":"$note","quizSize":2}"""
    }.andReturn().let { mvc.perform(asyncDispatch(it)) }

    @Test
    fun `validation errors list every field`() {
        mvc.post("/kotlin/chat/ask") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"question":"","source":"../etc/passwd"}"""
        }.andExpect {
            status { isBadRequest() }
            content { contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON) }
            jsonPath("$.code") { value("VALIDATION_FAILED") }
            jsonPath("$.type") { value("urn:rag:error:validation-failed") }
            jsonPath("$.status") { value(400) }
            jsonPath("$.errors.length()") { value(2) }
            jsonPath("$.errors[?(@.field == 'source')]") { exists() }
        }
    }

    @Test
    fun `malformed JSON, unknown route, wrong method, 404 and 409 all carry a code`() {
        mvc.post("/kotlin/chat/ask") { contentType = MediaType.APPLICATION_JSON; content = "{nope" }
            .andExpect { status { isBadRequest() }; jsonPath("$.code") { value("INVALID_REQUEST") } }
        mvc.get("/kotlin/nothing-here").andExpect { status { isNotFound() }; jsonPath("$.code") { value("NOT_FOUND") } }
        mvc.get("/kotlin/chat/ask").andExpect { status { isMethodNotAllowed() }; jsonPath("$.code") { value("INVALID_REQUEST") } }
        given(chatService.history("gone", null)).willThrow(NoSuchElementException("Unknown conversation 'gone'"))
        mvc.get("/kotlin/chat/sessions/gone").andExpect { status { isNotFound() }; jsonPath("$.detail") { value("Unknown conversation 'gone'") } }
        given(opsAgentService.confirm("o-9", true, null, null)).willThrow(ConversationStateException("nothing pending"))
        mvc.post("/kotlin/ops/confirm") { contentType = MediaType.APPLICATION_JSON; content = """{"conversationId":"o-9","approve":true}""" }
            .andExpect { status { isConflict() }; jsonPath("$.code") { value("CONFLICT") } }
    }

    @Test
    fun `provider timeout is 504, quota slot refunded`() {
        failure.set(OpenAIIoException("Request failed: timeout talking to api.openai.com", SocketTimeoutException("Read timed out")))
        generate("timeout-user")
            .andExpect(status().isGatewayTimeout)
            .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
            .andExpect(jsonPath("$.code").value("LLM_TIMEOUT"))
            .andExpect(jsonPath("$.detail").value(not(containsString("api.openai.com"))))
            .andExpect(jsonPath("$.trace").doesNotExist())
        val id = users.find("timeout-user")!!.id
        kotlin.test.assertEquals(0, usage.used(id, java.time.LocalDate.now(java.time.ZoneOffset.UTC)))
    }

    @Test
    fun `provider 429 and 5xx are 503 with Retry-After, bugs are a plain 500`() {
        failure.set(RateLimitException.builder().headers(Headers.builder().build()).build())
        generate("rl-user")
            .andExpect(status().isServiceUnavailable)
            .andExpect(header().string("Retry-After", "30"))
            .andExpect(jsonPath("$.code").value("LLM_RATE_LIMITED"))
        failure.set(InternalServerException.builder().statusCode(500).headers(Headers.builder().build()).build())
        generate("5xx-user")
            .andExpect(status().isServiceUnavailable)
            .andExpect(jsonPath("$.code").value("LLM_UNAVAILABLE"))
        failure.set(IllegalStateException("internal detail sk-should-not-leak"))
        generate("bug-user")
            .andExpect(status().isInternalServerError)
            .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
            .andExpect(jsonPath("$.detail").value(not(containsString("sk-"))))
    }
}
