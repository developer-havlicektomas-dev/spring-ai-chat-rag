package dev.havlicek.rag

import org.springframework.ai.vectorstore.filter.Filter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class RetrievalFilterTest {

    @Test
    fun `empty filter means no expression`() {
        assertNull(RetrievalFilter.of().toExpression())
    }

    @Test
    fun `single source is an equality`() {
        val e = RetrievalFilter.of(source = "coroutines.md").toExpression()!!
        assertEquals(Filter.ExpressionType.EQ, e.type())
        assertEquals(Filter.Key("source"), e.left())
        assertEquals(Filter.Value("coroutines.md"), e.right())
    }

    @Test
    fun `sources and topics are AND-ed, lists become IN`() {
        val e = RetrievalFilter.of(sources = listOf("data-classes.md", "sealed-classes.md"), topics = listOf("classes")).toExpression()!!
        assertEquals(Filter.ExpressionType.AND, e.type())
        val sources = e.left() as Filter.Expression
        assertEquals(Filter.ExpressionType.IN, sources.type())
        assertEquals(Filter.Value(listOf("data-classes.md", "sealed-classes.md")), sources.right())
        assertEquals(Filter.ExpressionType.EQ, (e.right() as Filter.Expression).type())
    }

    @Test
    fun `legacy source merges with sources, duplicates and blanks dropped, topics lowercased`() {
        val f = RetrievalFilter.of("coroutines.md", listOf("coroutines.md", " ", "null-safety.md"), listOf("Type-System"))
        assertEquals(listOf("coroutines.md", "null-safety.md"), f.sources)
        assertEquals(listOf("type-system"), f.topics)
    }

    @Test
    fun `unsafe values are rejected`() {
        assertFailsWith<InvalidFilterException> { RetrievalFilter.of(sources = listOf("x' || true")) }
        assertFailsWith<InvalidFilterException> { RetrievalFilter.of(topics = listOf("a b")) }
        assertFailsWith<InvalidFilterException> { RetrievalFilter.of(sources = (1..11).map { "d$it.md" }) }
    }

    @Test
    fun `chunk settings are validated`() {
        assertEquals(200, ChunkSettings(200, 50).chunkSize)
        assertFailsWith<InvalidChunkSettingsException> { ChunkSettings(8, 0) }
        assertFailsWith<InvalidChunkSettingsException> { ChunkSettings(100, 60) }
    }
}
