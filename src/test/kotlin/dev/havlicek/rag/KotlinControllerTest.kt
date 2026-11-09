package dev.havlicek.rag

import org.junit.jupiter.api.Test
import org.mockito.BDDMockito.given
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest
import org.springframework.http.MediaType
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post

/** Web-slice test: no OpenAI key or Qdrant needed. */
@WebMvcTest(KotlinController::class)
class KotlinControllerTest(@Autowired private val mvc: MockMvc) {

    @MockitoBean lateinit var docsService: DocsService
    @MockitoBean lateinit var chatService: ChatService

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
}
