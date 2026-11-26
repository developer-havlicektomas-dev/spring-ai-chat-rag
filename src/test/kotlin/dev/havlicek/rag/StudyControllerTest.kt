package dev.havlicek.rag

import org.hamcrest.Matchers.containsString
import org.junit.jupiter.api.Test
import org.springframework.ai.chat.client.ChatClient
import org.springframework.ai.chat.messages.AssistantMessage
import org.springframework.ai.chat.model.ChatModel
import org.springframework.ai.chat.model.ChatResponse
import org.springframework.ai.chat.model.Generation
import org.springframework.ai.chat.prompt.Prompt
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.core.io.ClassPathResource
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.ResultActions
import org.springframework.test.web.servlet.post
import org.springframework.test.web.servlet.put
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.header
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

/**
 * F9: HTTP contract of the Study Guide endpoints with the REAL guard + service (in-memory stores, scripted ChatModel):
 * 200 body, 402 plan limit, 429 quota, 400 validation, dev plan switch hidden by default.
 */
@WebMvcTest(StudyController::class)
@Import(StudyControllerTest.Beans::class)
class StudyControllerTest(@Autowired private val mvc: MockMvc) {

    @TestConfiguration
    @EnableConfigurationProperties(StudyProperties::class, RagProperties::class)
    class Beans {
        private val chatModel = object : ChatModel {
            override fun call(prompt: Prompt) = ChatResponse(listOf(Generation(AssistantMessage(StudyGuideServiceTest.GOOD_DRAFT))))
        }

        @Bean fun userDirectory(): UserDirectory = InMemoryUserDirectory()
        @Bean fun studyUsageStore(): StudyUsageStore = InMemoryStudyUsageStore()
        @Bean fun studyGuard(users: UserDirectory, usage: StudyUsageStore, props: StudyProperties) = StudyGuard(users, usage, props)
        @Bean fun studyGuideService(guard: StudyGuard, usage: StudyUsageStore, props: StudyProperties) = StudyGuideService(
            ChatClient.builder(chatModel), guard, usage, props,
            ClassPathResource("prompts/study-system.st"), ClassPathResource("prompts/study-generate.st"), ClassPathResource("prompts/study-fallback.st"),
        )
    }

    private val note = "Photosynthesis happens in the chloroplasts of plant cells.\\n" +
        "The light-dependent reactions take place in the thylakoid membranes and produce ATP and NADPH.\\n\\n" +
        "The Calvin cycle runs in the stroma and uses ATP and NADPH to fix carbon dioxide into glucose."

    private fun postGenerate(body: String, user: String) = mvc.post("/kotlin/study/generate") {
        contentType = MediaType.APPLICATION_JSON
        header(USER_HEADER, user)
        content = body
    }

    /** suspend controller -> async dispatch */
    private fun generate(body: String, user: String): ResultActions = postGenerate(body, user).andReturn().let { mvc.perform(asyncDispatch(it)) }

    @Test
    fun `free user gets a grounded guide, then 402 for pro features and 429 after the daily quota`() {
        generate("""{"text":"$note","title":"Photosynthesis","quizSize":2}""", "free-web")
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.answerType").value("grounded"))
            .andExpect(jsonPath("$.quiz[0].source.chunkId").value("C1"))
            .andExpect(jsonPath("$.quiz[0].source.start").isNumber)
            .andExpect(jsonPath("$.usage.plan").value("free"))
            .andExpect(jsonPath("$.usage.usedToday").value(1))

        generate("""{"text":"$note","quizSize":10}""", "free-web")
            .andExpect(status().isPaymentRequired)
            .andExpect(jsonPath("$.code").value("PLAN_LIMIT"))
            .andExpect(jsonPath("$.reason").value("quizSize"))
            .andExpect(jsonPath("$.limit").value(5))
            .andExpect(jsonPath("$.plan").value("free"))
            .andExpect(jsonPath("$.upgradeTo").value("pro"))
        generate("""{"text":"$note","features":["FLASHCARDS"]}""", "free-web")
            .andExpect(status().isPaymentRequired)
            .andExpect(jsonPath("$.reason").value("feature"))

        repeat(2) { generate("""{"text":"$note","quizSize":2}""", "free-web").andExpect(status().isOk) }
        generate("""{"text":"$note","quizSize":2}""", "free-web")
            .andExpect(status().isTooManyRequests)
            .andExpect(header().exists("Retry-After"))
            .andExpect(jsonPath("$.code").value("DAILY_QUOTA_EXCEEDED"))
            .andExpect(jsonPath("$.used").value(3))
            .andExpect(jsonPath("$.limit").value(3))
    }

    @Test
    fun `blank text, unknown feature, bad quiz size and bad user id are 400`() {
        postGenerate("""{"text":"   "}""", "alice").andExpect { status { isBadRequest() } }
        postGenerate("""{"text":"ok","features":["MAGIC"]}""", "alice").andExpect { status { isBadRequest() } }
        postGenerate("""{"text":"ok","quizSize":0}""", "alice").andExpect { status { isBadRequest() } }
        generate("""{"text":"ok"}""", "bad user!")
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.error").value(containsString("X-User-Id")))
    }

    @Test
    fun `dev plan switch is hidden unless enabled`() {
        mvc.put("/kotlin/study/plan") {
            contentType = MediaType.APPLICATION_JSON
            header(USER_HEADER, "alice")
            content = """{"plan":"pro"}"""
        }.andExpect { status { isNotFound() } }
    }
}
