package dev.havlicek.rag

import io.qdrant.client.QdrantClient
import io.qdrant.client.QdrantGrpcClient
import org.springframework.ai.document.Document
import org.springframework.ai.embedding.EmbeddingModel
import org.springframework.ai.embedding.EmbeddingRequest
import org.springframework.ai.embedding.EmbeddingResponse
import org.springframework.ai.vectorstore.SearchRequest
import org.springframework.ai.vectorstore.VectorStore
import org.springframework.ai.vectorstore.pgvector.PgVectorStore
import org.springframework.ai.vectorstore.pgvector.autoconfigure.PgVectorStoreProperties
import org.springframework.ai.vectorstore.qdrant.QdrantVectorStore
import org.springframework.ai.vectorstore.qdrant.autoconfigure.QdrantVectorStoreProperties
import org.springframework.beans.factory.DisposableBean
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Configuration
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component
import java.util.concurrent.ConcurrentHashMap

/**
 * F8: binds both stores' spring.ai.vectorstore.* properties even when only one auto-configuration is active
 * (spring.ai.vectorstore.type = rag.vector-store), so the inactive store can be built with the same settings.
 */
@Configuration
@EnableConfigurationProperties(QdrantVectorStoreProperties::class, PgVectorStoreProperties::class)
class VectorStoreConfig

/**
 * F8: hands out a [VectorStore] per [StoreKind]. The active kind is the auto-configured `VectorStore` bean that RAG,
 * DocsService, the agents and evals use; the other one is built on demand (for ingest + comparison only).
 */
@Component
class VectorStoreRegistry(
    private val active: VectorStore,
    private val props: RagProperties,
    private val embeddingModel: EmbeddingModel,
    private val jdbcTemplate: ObjectProvider<JdbcTemplate>,
    private val qdrantClientBean: ObjectProvider<QdrantClient>,
    private val qdrantProps: QdrantVectorStoreProperties,
    private val pgProps: PgVectorStoreProperties,
) : DisposableBean {
    private val others = ConcurrentHashMap<StoreKind, VectorStore>()
    private var ownQdrantClient: QdrantClient? = null

    val activeKind: StoreKind get() = props.vectorStore

    fun store(kind: StoreKind): VectorStore =
        if (kind == activeKind) active else others.computeIfAbsent(kind) { build(it, embeddingModel, initializeSchema = true) }

    /** A new store instance with the same settings the auto-configuration uses, but [embeddings] of our choice. */
    fun build(kind: StoreKind, embeddings: EmbeddingModel, initializeSchema: Boolean): VectorStore = when (kind) {
        StoreKind.QDRANT -> QdrantVectorStore.builder(qdrantClient(), embeddings)
            .collectionName(qdrantProps.collectionName)
            .initializeSchema(initializeSchema)
            .build()
        StoreKind.PGVECTOR -> PgVectorStore.builder(
            jdbcTemplate.ifAvailable ?: error("pgvector needs Postgres (rag.persistence=jdbc / no 'memory' profile)"),
            embeddings,
        )
            .schemaName(pgProps.schemaName)
            .vectorTableName(pgProps.tableName)
            .dimensions(pgProps.dimensions)
            .distanceType(pgProps.distanceType)
            .indexType(pgProps.indexType)
            .initializeSchema(initializeSchema)
            .build()
    }.also { (it as org.springframework.beans.factory.InitializingBean).afterPropertiesSet() } // creates schema if asked

    @Synchronized
    private fun qdrantClient(): QdrantClient =
        qdrantClientBean.ifAvailable ?: ownQdrantClient ?: QdrantClient(
            QdrantGrpcClient.newBuilder(qdrantProps.host, qdrantProps.port, qdrantProps.isUseTls)
                .apply { qdrantProps.apiKey?.let { withApiKey(it) } }
                .build()
        ).also { ownQdrantClient = it }

    override fun destroy() {
        ownQdrantClient?.close()
    }
}

data class HitRef(val source: String?, val chunkIndex: Int?, val score: Double?) {
    val key get() = "$source#$chunkIndex"
}

data class LatencyStats(val p50Ms: Double, val p95Ms: Double, val meanMs: Double, val samples: Int) {
    companion object {
        fun of(samplesMs: List<Double>): LatencyStats {
            require(samplesMs.isNotEmpty()) { "no samples" }
            val sorted = samplesMs.sorted()
            fun pct(p: Double) = sorted[((p * sorted.size).toInt()).coerceAtMost(sorted.size - 1)].round2()
            return LatencyStats(pct(0.50), pct(0.95), sorted.average().round2(), sorted.size)
        }
    }
}

data class QueryComparison(
    val query: String,
    /** |top-k(A) ∩ top-k(B)| / k, by (source, chunk_index) since document ids differ per store */
    val overlap: Double,
    val sameTop1: Boolean,
    val sameOrder: Boolean,
    /** largest |score(A) - score(B)| over the chunks both returned (both report cosine similarity) */
    val maxScoreDelta: Double?,
    val hits: Map<StoreKind, List<HitRef>>,
)

data class StoreComparisonReport(
    val activeStore: StoreKind,
    val topK: Int,
    val repeats: Int,
    val queries: Int,
    /** search latency per store with the query embedding cached (= store time, not OpenAI time) */
    val searchLatency: Map<StoreKind, LatencyStats>,
    /** one embedding call per query (what every real search pays on top) */
    val embeddingLatency: LatencyStats,
    val meanOverlap: Double,
    val sameTop1: Int,
    val sameOrder: Int,
    val maxScoreDelta: Double?,
    val chunks: Map<StoreKind, Int>,
    val differences: List<QueryComparison>,
    val perQuery: List<QueryComparison>,
)

/**
 * F8: runs the same queries against Qdrant and pgvector. Each query is embedded once (timed separately), then every
 * store is searched [repeats] times with that cached vector, alternating the store order, so the latency numbers
 * compare the stores themselves rather than the OpenAI embedding call.
 */
@Component
class VectorStoreComparator(private val registry: VectorStoreRegistry, private val embeddingModel: EmbeddingModel) {
    private val cachingEmbeddings by lazy { CachingEmbeddingModel(embeddingModel) }
    private val stores by lazy { StoreKind.entries.associateWith { registry.build(it, cachingEmbeddings, initializeSchema = false) } }

    fun compare(queries: List<String>, topK: Int, repeats: Int): StoreComparisonReport {
        val embeddingMs = queries.map { q -> timed { cachingEmbeddings.embed(q) }.second }
        return compare(stores, queries, topK, repeats, registry.activeKind, LatencyStats.of(embeddingMs))
    }

    companion object {
        fun compare(
            stores: Map<StoreKind, VectorStore>,
            queries: List<String>,
            topK: Int,
            repeats: Int,
            activeStore: StoreKind,
            embeddingLatency: LatencyStats,
        ): StoreComparisonReport {
            require(stores.size == 2) { "need exactly two stores" }
            val (a, b) = stores.keys.toList()
            fun search(store: VectorStore, q: String) =
                store.similaritySearch(SearchRequest.builder().query(q).topK(topK).similarityThresholdAll().build())
                    .map(::hitRef)

            // warm-up: connection pools, JIT, HNSW pages - and the results we compare
            val results = queries.associateWith { q -> stores.mapValues { (_, s) -> search(s, q) } }
            val latencies = stores.keys.associateWith { mutableListOf<Double>() }
            repeat(repeats) { r ->
                queries.forEach { q ->
                    val order = if (r % 2 == 0) listOf(a, b) else listOf(b, a)
                    order.forEach { kind -> latencies.getValue(kind) += timed { search(stores.getValue(kind), q) }.second }
                }
            }
            val perQuery = results.map { (q, hits) -> compareHits(q, hits, topK, a, b) }
            val chunks = stores.mapValues { (_, s) ->
                s.similaritySearch(SearchRequest.builder().query("Kotlin").topK(1000).similarityThresholdAll().build()).size
            }
            return StoreComparisonReport(
                activeStore = activeStore,
                topK = topK,
                repeats = repeats,
                queries = queries.size,
                searchLatency = latencies.mapValues { LatencyStats.of(it.value) },
                embeddingLatency = embeddingLatency,
                meanOverlap = perQuery.map { it.overlap }.average().round2(),
                sameTop1 = perQuery.count { it.sameTop1 },
                sameOrder = perQuery.count { it.sameOrder },
                maxScoreDelta = perQuery.mapNotNull { it.maxScoreDelta }.maxOrNull(),
                chunks = chunks,
                differences = perQuery.filter { !it.sameOrder },
                perQuery = perQuery,
            )
        }

        fun compareHits(query: String, hits: Map<StoreKind, List<HitRef>>, topK: Int, a: StoreKind, b: StoreKind): QueryComparison {
            val ha = hits.getValue(a)
            val hb = hits.getValue(b)
            val shared = ha.map { it.key }.toSet() intersect hb.map { it.key }.toSet()
            val deltas = shared.mapNotNull { key ->
                val sa = ha.first { it.key == key }.score
                val sb = hb.first { it.key == key }.score
                if (sa != null && sb != null) kotlin.math.abs(sa - sb) else null
            }
            return QueryComparison(
                query = query,
                overlap = (shared.size.toDouble() / topK).round2(),
                sameTop1 = ha.firstOrNull()?.key == hb.firstOrNull()?.key,
                sameOrder = ha.map { it.key } == hb.map { it.key },
                maxScoreDelta = deltas.maxOrNull()?.let { "%.6f".format(java.util.Locale.ROOT, it).toDouble() },
                hits = hits,
            )
        }

        private fun hitRef(d: Document) =
            HitRef(d.metadata["source"]?.toString(), (d.metadata["chunk_index"] as? Number)?.toInt(), d.score?.round4())
    }
}

/** F8: memoizes query embeddings (search path only) so repeated searches measure the store, not OpenAI. */
class CachingEmbeddingModel(private val delegate: EmbeddingModel) : EmbeddingModel {
    private val cache = ConcurrentHashMap<String, FloatArray>()
    override fun call(request: EmbeddingRequest): EmbeddingResponse = delegate.call(request)
    override fun embed(document: Document): FloatArray = delegate.embed(document)
    override fun embed(text: String): FloatArray = cache.computeIfAbsent(text) { delegate.embed(it) }
    override fun dimensions(): Int = delegate.dimensions()
}

private inline fun <T> timed(block: () -> T): Pair<T, Double> {
    val start = System.nanoTime()
    val result = block()
    return result to (System.nanoTime() - start) / 1_000_000.0
}

private fun Double.round2() = Math.round(this * 100) / 100.0
private fun Double.round4() = Math.round(this * 10_000) / 10_000.0
