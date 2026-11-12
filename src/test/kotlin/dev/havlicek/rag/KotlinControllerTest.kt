package dev.havlicek.rag

import org.junit.jupiter.api.Test
import org.mockito.BDDMockito.given
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest
import org.springframework.http.MediaType
import org.springframework.http.codec.ServerSentEvent
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import reactor.core.publisher.Flux
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post

/** Web-slice test: no OpenAI key or Qdrant needed. */
@WebMvcTest(KotlinController::class)
class KotlinControllerTest(@Autowired private val mvc: MockMvc) {

    @MockitoBean lateinit var docsService: DocsService
    @MockitoBean lateinit var chatService: ChatService
    @MockitoBean lateinit var koogBridgeService: KoogBridgeService
    @MockitoBean lateinit var koogAgentService: KoogAgentService

    @Test
    fun `ask returns the answer with sources`() {
        given(chatService.ask("What is a data class?", null))
            .willReturn(RagAnswer("A class holding data.", listOf(SourceRef("data-classes.md", 0.82))))
        mvc.post("/kotlin/chat/ask") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"question":"  What is a data class?  "}"""
        }.andExpect {
            status { isOk() }
            jsonPath("$.answer") { value("A class holding data.") }
            jsonPath("$.sources[0].source") { value("data-classes.md") }
        }
    }

    @Test
    fun `ask passes the source filter`() {
        given(chatService.ask("launch vs async?", "coroutines.md")).willReturn(RagAnswer("Use async for results.", emptyList()))
        mvc.post("/kotlin/chat/ask") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"question":"launch vs async?","source":"coroutines.md"}"""
        }.andExpect {
            status { isOk() }
            jsonPath("$.answer") { value("Use async for results.") }
        }
    }

    @Test
    fun `blank question is rejected`() {
        mvc.post("/kotlin/chat/ask") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"question":"   "}"""
        }.andExpect { status { isBadRequest() } }
    }

    @Test
    fun `source with quotes is rejected`() {
        mvc.post("/kotlin/chat/ask") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"question":"hi","source":"x' || true"}"""
        }.andExpect { status { isBadRequest() } }
    }

    @Test
    fun `search returns hits`() {
        given(docsService.search("null", 4)).willReturn(listOf(SearchHit("null-safety.md", 0.9, "text")))
        mvc.get("/kotlin/docs/search?q=null").andExpect {
            status { isOk() }
            jsonPath("$[0].source") { value("null-safety.md") }
        }
    }

    @Test
    fun `stream emits server-sent events`() {
        given(chatService.stream("What is a data class?", null)).willReturn(
            Flux.just(
                ServerSentEvent.builder<Any>(TokenChunk(" class")).event("token").build(),
                ServerSentEvent.builder<Any>("[DONE]").event("done").build(),
            )
        )
        val started = mvc.post("/kotlin/chat/stream") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"question":"What is a data class?"}"""
        }.andExpect { request { asyncStarted() } }.andReturn()

        mvc.perform(asyncDispatch(started))
            .andExpect(status().isOk)
            .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_EVENT_STREAM))
            .andExpect(content().string(org.hamcrest.Matchers.containsString("event:token\ndata:{\"text\":\" class\"}")))
            .andExpect(content().string(org.hamcrest.Matchers.containsString("event:done")))
    }

    @Test
    fun `stream rejects blank question`() {
        mvc.post("/kotlin/chat/stream") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"question":""}"""
        }.andExpect { status { isBadRequest() } }
    }

    @Test
    fun `koog ask rejects blank question`() {
        mvc.post("/kotlin/koog/ask") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"question":"  "}"""
        }.andExpect { status { isBadRequest() } }
    }

    @Test
    fun `agent ask rejects blank question`() {
        mvc.post("/kotlin/agent/ask") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"question":""}"""
        }.andExpect { status { isBadRequest() } }
    }
}
