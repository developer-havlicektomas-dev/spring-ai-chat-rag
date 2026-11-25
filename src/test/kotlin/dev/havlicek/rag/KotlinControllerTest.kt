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
    @MockitoBean lateinit var koogStudyService: KoogStudyService
    @MockitoBean lateinit var opsAgentService: OpsAgentService
    @MockitoBean lateinit var ragEvalRunner: RagEvalRunner

    @Test
    fun `unknown eval variant is a bad request`() {
        mvc.post("/kotlin/evals/run") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"variants":["baseline","turbo"]}"""
        }.andExpect {
            status { isBadRequest() }
            jsonPath("$.error") { value(org.hamcrest.Matchers.containsString("turbo")) }
        }
    }

    @Test
    fun `ops ask returns pending tool calls`() {
        given(opsAgentService.ask("Delete coroutines.md", null)).willReturn(
            OpsAnswer("o-1", "awaiting_confirmation", null, listOf(PendingToolCall("call_1", "deleteDocSource", "{}")))
        )
        mvc.post("/kotlin/ops/ask") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"message":"Delete coroutines.md"}"""
        }.andExpect {
            status { isOk() }
            jsonPath("$.status") { value("awaiting_confirmation") }
            jsonPath("$.pendingToolCalls[0].tool") { value("deleteDocSource") }
        }
    }

    @Test
    fun `ops confirm without a pending call is a conflict`() {
        given(opsAgentService.confirm("o-2", true, null, null)).willThrow(ConversationStateException("nothing pending"))
        mvc.post("/kotlin/ops/confirm") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"conversationId":"o-2","approve":true}"""
        }.andExpect { status { isConflict() } }
    }

    @Test
    fun `ask returns the answer with sources`() {
        given(chatService.ask("What is a data class?", AskOptions()))
            .willReturn(RagAnswer("A class holding data [1].", listOf(SourceRef("data-classes.md", 0.82)), "c-1",
                citations = listOf(Citation(1, "data-classes.md", "classes", 0, "id-1", 0.82, null, null, "A data class...", true))))
        mvc.post("/kotlin/chat/ask") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"question":"  What is a data class?  "}"""
        }.andExpect {
            status { isOk() }
            jsonPath("$.answer") { value("A class holding data [1].") }
            jsonPath("$.sources[0].source") { value("data-classes.md") }
            jsonPath("$.conversationId") { value("c-1") }
            jsonPath("$.answerType") { value("grounded") }
            jsonPath("$.citations[0].n") { value(1) }
            jsonPath("$.citations[0].chunkIndex") { value(0) }
        }
    }

    @Test
    fun `ask passes the source filter`() {
        given(chatService.ask("launch vs async?", AskOptions(RetrievalFilter(listOf("coroutines.md"))))).willReturn(RagAnswer("Use async for results.", emptyList(), "c-2"))
        mvc.post("/kotlin/chat/ask") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"question":"launch vs async?","source":"coroutines.md"}"""
        }.andExpect {
            status { isOk() }
            jsonPath("$.answer") { value("Use async for results.") }
        }
    }

    @Test
    fun `ask passes metadata filters and decision overrides`() {
        val options = AskOptions(RetrievalFilter(listOf("data-classes.md", "sealed-classes.md"), listOf("classes")), null, rerank = true, decisionFilter = false)
        given(chatService.ask("copy()?", options)).willReturn(
            RagAnswer("[General knowledge - not from your docs] ...", emptyList(), "c-3", AnswerType.GENERAL_KNOWLEDGE_FALLBACK, sufficiency = 0.2)
        )
        mvc.post("/kotlin/chat/ask") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"question":"copy()?","sources":["data-classes.md","sealed-classes.md"],"topics":["classes"],"rerank":true,"decisionFilter":false}"""
        }.andExpect {
            status { isOk() }
            jsonPath("$.answerType") { value("general_knowledge_fallback") }
            jsonPath("$.sufficiency") { value(0.2) }
        }
    }

    @Test
    fun `invalid topic is a bad request with a message`() {
        mvc.post("/kotlin/chat/ask") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"question":"hi","topics":["classes' || true"]}"""
        }.andExpect {
            status { isBadRequest() }
            jsonPath("$.error") { value(org.hamcrest.Matchers.containsString("Invalid topic")) }
        }
    }

    @Test
    fun `invalid source in list is a bad request`() {
        mvc.post("/kotlin/chat/ask") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"question":"hi","sources":["../secrets"]}"""
        }.andExpect { status { isBadRequest() } }
    }

    @Test
    fun `too many sources is a bad request`() {
        val many = (1..11).joinToString(",") { "\"d$it.md\"" }
        mvc.post("/kotlin/chat/ask") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"question":"hi","sources":[$many]}"""
        }.andExpect { status { isBadRequest() } }
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
    fun `ask continues a conversation`() {
        given(chatService.ask("And how do I make one?", AskOptions(conversationId = "c-1"))).willReturn(RagAnswer("Use sealed class.", emptyList(), "c-1"))
        mvc.post("/kotlin/chat/ask") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"question":"And how do I make one?","conversationId":"c-1"}"""
        }.andExpect {
            status { isOk() }
            jsonPath("$.conversationId") { value("c-1") }
        }
    }

    @Test
    fun `odd conversation id is rejected`() {
        mvc.post("/kotlin/chat/ask") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"question":"hi","conversationId":"../etc"}"""
        }.andExpect { status { isBadRequest() } }
    }

    @Test
    fun `history of unknown session is 404`() {
        given(chatService.history("nope", null)).willReturn(null)
        mvc.get("/kotlin/chat/sessions/nope").andExpect { status { isNotFound() } }
    }

    @Test
    fun `history returns session messages`() {
        given(chatService.history("c-1", null)).willReturn(listOf(HistoryMessage("user", "hi"), HistoryMessage("assistant", "hello")))
        mvc.get("/kotlin/chat/sessions/c-1").andExpect {
            status { isOk() }
            jsonPath("$[1].role") { value("assistant") }
        }
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
        given(chatService.stream("What is a data class?", AskOptions())).willReturn(
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

    @Test
    fun `agent study rejects blank and too long questions`() {
        mvc.post("/kotlin/agent/study") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"question":"   "}"""
        }.andExpect { status { isBadRequest() } }
        mvc.post("/kotlin/agent/study") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"question":"${"x".repeat(2001)}"}"""
        }.andExpect { status { isBadRequest() } }
    }

    @Test
    fun `agent study graph is served as mermaid text`() {
        given(koogStudyService.mermaid).willReturn("stateDiagram\n    [*] --> research")
        mvc.get("/kotlin/agent/study/graph").andExpect {
            status { isOk() }
            content { string(org.hamcrest.Matchers.containsString("research")) }
        }
    }

    @Test
    fun `X-User-Id header owns the conversation`() {
        given(chatService.ask("What is a data class?", AskOptions(userId = "alice"))).willReturn(RagAnswer("A data class.", emptyList(), "c-9"))
        mvc.post("/kotlin/chat/ask") {
            contentType = MediaType.APPLICATION_JSON
            header(USER_HEADER, "alice")
            content = """{"question":"What is a data class?"}"""
        }.andExpect {
            status { isOk() }
            jsonPath("$.conversationId") { value("c-9") }
        }
    }

    @Test
    fun `invalid X-User-Id is a bad request`() {
        mvc.post("/kotlin/chat/ask") {
            contentType = MediaType.APPLICATION_JSON
            header(USER_HEADER, "alice; drop table")
            content = """{"question":"What is a data class?"}"""
        }.andExpect {
            status { isBadRequest() }
            jsonPath("$.error") { value(org.hamcrest.Matchers.containsString("X-User-Id")) }
        }
    }

    @Test
    fun `sessions lists the caller's conversations`() {
        given(chatService.sessions("alice")).willReturn(listOf(SessionSummary("c-1", java.time.Instant.parse("2026-11-25T07:00:00Z"), null, 4)))
        mvc.get("/kotlin/chat/sessions") { header(USER_HEADER, "alice") }.andExpect {
            status { isOk() }
            jsonPath("$[0].conversationId") { value("c-1") }
            jsonPath("$[0].messages") { value(4) }
        }
    }

    @Test
    fun `history of another user's session is 404`() {
        given(chatService.history("c-1", "bob")).willReturn(null)
        mvc.get("/kotlin/chat/sessions/c-1") { header(USER_HEADER, "bob") }.andExpect { status { isNotFound() } }
    }
}
