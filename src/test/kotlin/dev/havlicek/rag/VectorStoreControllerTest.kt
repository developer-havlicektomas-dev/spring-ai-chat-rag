package dev.havlicek.rag

import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.ArgumentMatchers.anyList
import org.mockito.BDDMockito.given
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest
import org.springframework.http.MediaType
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.post

/** F8: web slice for /kotlin/vectorstores (no stores needed). */
@WebMvcTest(VectorStoreController::class)
class VectorStoreControllerTest(@Autowired private val mvc: MockMvc) {
    @MockitoBean lateinit var registry: VectorStoreRegistry
    @MockitoBean lateinit var comparator: VectorStoreComparator
    @MockitoBean lateinit var docsService: DocsService

    @Test
    fun `unknown store is a bad request`() {
        mvc.post("/kotlin/vectorstores/chroma/ingest").andExpect {
            status { isBadRequest() }
            jsonPath("$.error") { value(org.hamcrest.Matchers.containsString("chroma")) }
        }
    }

    @Test
    fun `compare validates topK and repeats`() {
        mvc.post("/kotlin/vectorstores/compare") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"topK":0,"repeats":500}"""
        }.andExpect { status { isBadRequest() } }
    }

    @Test
    fun `compare without a body uses the golden questions`() {
        val report = StoreComparisonReport(
            StoreKind.QDRANT, 4, 5, 20, emptyMap(), LatencyStats.of(listOf(1.0)), 1.0, 20, 20, 0.0, emptyMap(), emptyList(), emptyList(),
        )
        given(comparator.compare(anyList(), anyInt(), anyInt())).willReturn(report)
        mvc.post("/kotlin/vectorstores/compare").andExpect {
            status { isOk() }
            jsonPath("$.meanOverlap") { value(1.0) }
            jsonPath("$.activeStore") { value("qdrant") }
        }
    }
}
