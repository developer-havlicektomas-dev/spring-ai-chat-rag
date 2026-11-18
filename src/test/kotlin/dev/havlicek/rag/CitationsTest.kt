package dev.havlicek.rag

import org.springframework.ai.document.Document
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CitationsTest {
    private val docs = listOf(
        Document.builder().id("a").text("Coroutines are   lightweight.\nUse launch.").score(0.71)
            .metadata(mapOf("source" to "coroutines.md", "chunk_index" to 0L, "topic" to "concurrency")).build(),
        Document.builder().id("b").text("x".repeat(300)).score(0.55)
            .metadata(mapOf("source" to "null-safety.md", "chunk_index" to 3)).build(),
    )

    @Test
    fun `context is numbered in list order with source and chunk`() {
        val ctx = Citations.formatContext(docs)
        assertTrue(ctx.startsWith("[1] (coroutines.md#0)\nCoroutines are"))
        assertTrue("\n\n[2] (null-safety.md#3)\n" in ctx)
    }

    @Test
    fun `citations carry metadata, a short snippet and whether the answer cited them`() {
        val c = Citations.from(docs, "Use launch [1]. Also see [1][7].")
        assertEquals(listOf(1, 2), c.map { it.n })
        assertEquals("coroutines.md", c[0].source)
        assertEquals("concurrency", c[0].topic)
        assertEquals(0, c[0].chunkIndex)
        assertEquals("a", c[0].chunkId)
        assertEquals(0.71, c[0].score)
        assertEquals("Coroutines are lightweight. Use launch.", c[0].snippet)
        assertTrue(c[0].cited)
        assertEquals(false, c[1].cited)
        assertEquals(160, c[1].snippet.length)
    }

    @Test
    fun `cited numbers are parsed from markers`() {
        assertEquals(setOf(1, 2, 3), Citations.citedNumbers("a [1] b [2][3] c [x]"))
    }
}
