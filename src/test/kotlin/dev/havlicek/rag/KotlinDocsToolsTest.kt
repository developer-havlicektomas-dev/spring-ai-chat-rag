package dev.havlicek.rag

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.BDDMockito.given

/** Day 10: the tool is plain Kotlin, so it can be unit-tested without an LLM or Qdrant. */
class KotlinDocsToolsTest {

    private val docsService: DocsService = mock(DocsService::class.java)
    private val tools = KotlinDocsTools(docsService)

    @Test
    fun `formats hits with their source`() {
        given(docsService.search("sealed", 2)).willReturn(
            listOf(SearchHit("sealed-classes.md", 0.9, "Direct subclasses must be in the same package."))
        )
        val result = tools.searchKotlinDocs("sealed", 2)
        assertTrue(result.startsWith("[source: sealed-classes.md]"))
        assertTrue(result.contains("same package"))
    }

    @Test
    fun `clamps topK the model asks for`() {
        given(docsService.search("x", 8)).willReturn(emptyList())
        assertEquals("No matching docs found.", tools.searchKotlinDocs("x", 50))
        verify(docsService).search("x", 8)
    }
}
