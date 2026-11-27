package dev.havlicek.rag

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.ai.vectorstore.SimpleVectorStore

/** F8: the comparison maths on two in-memory SimpleVectorStores - no Qdrant, Postgres or OpenAI. */
class VectorStoreComparatorTest {
    private val queries = listOf("What is a sealed class?", "data class copy", "launch a coroutine")

    private fun store(chunks: List<org.springframework.ai.document.Document>) =
        SimpleVectorStore.builder(KeywordEmbeddingModel()).build().apply { add(chunks) }

    @Test
    fun `identical stores agree on every query`() {
        val report = VectorStoreComparator.compare(
            mapOf(StoreKind.QDRANT to store(SAMPLE_CHUNKS), StoreKind.PGVECTOR to store(SAMPLE_CHUNKS)),
            queries, topK = 2, repeats = 3, activeStore = StoreKind.QDRANT, embeddingLatency = LatencyStats.of(listOf(1.0)),
        )
        assertEquals(1.0, report.meanOverlap)
        assertEquals(3, report.sameTop1)
        assertEquals(3, report.sameOrder)
        assertTrue(report.differences.isEmpty())
        assertEquals(0.0, report.maxScoreDelta)
        assertEquals(mapOf(StoreKind.QDRANT to 4, StoreKind.PGVECTOR to 4), report.chunks)
        assertEquals(9, report.searchLatency.getValue(StoreKind.PGVECTOR).samples) // 3 queries x 3 repeats
        assertEquals("sealed-classes.md", report.perQuery.first().hits.getValue(StoreKind.QDRANT).first().source)
    }

    @Test
    fun `a store missing a document shows up as a difference`() {
        val report = VectorStoreComparator.compare(
            mapOf(StoreKind.QDRANT to store(SAMPLE_CHUNKS), StoreKind.PGVECTOR to store(SAMPLE_CHUNKS.drop(1))),
            listOf("What is a sealed class?"), topK = 2, repeats = 1, activeStore = StoreKind.PGVECTOR,
            embeddingLatency = LatencyStats.of(listOf(1.0)),
        )
        val q = report.differences.single()
        assertFalse(q.sameTop1)
        assertEquals(0.5, q.overlap) // 1 of the 2 Qdrant hits also came back from "pgvector"
    }

    @Test
    fun `overlap and score delta are computed by source and chunk index`() {
        val a = listOf(HitRef("a.md", 0, 0.9), HitRef("b.md", 1, 0.8), HitRef("c.md", 0, 0.7))
        val b = listOf(HitRef("a.md", 0, 0.8999), HitRef("c.md", 0, 0.7), HitRef("d.md", 2, 0.6))
        val q = VectorStoreComparator.compareHits("q", mapOf(StoreKind.QDRANT to a, StoreKind.PGVECTOR to b), 3, StoreKind.QDRANT, StoreKind.PGVECTOR)
        assertEquals(0.67, q.overlap)
        assertTrue(q.sameTop1)
        assertFalse(q.sameOrder)
        assertEquals(0.0001, q.maxScoreDelta)
    }

    @Test
    fun `latency percentiles`() {
        val stats = LatencyStats.of((1..100).map { it.toDouble() })
        assertEquals(51.0, stats.p50Ms)
        assertEquals(96.0, stats.p95Ms)
        assertEquals(50.5, stats.meanMs)
    }

    @Test
    fun `caching embedding model embeds each query once`() {
        val delegate = KeywordEmbeddingModel()
        val caching = CachingEmbeddingModel(delegate)
        repeat(3) { caching.embed("sealed class") }
        assertEquals(1, delegate.calls)
    }

    @Test
    fun `store names parse case-insensitively and reject unknown ones`() {
        assertEquals(StoreKind.PGVECTOR, StoreKind.of("PgVector"))
        org.junit.jupiter.api.assertThrows<IllegalArgumentException> { StoreKind.of("chroma") }
    }
}
