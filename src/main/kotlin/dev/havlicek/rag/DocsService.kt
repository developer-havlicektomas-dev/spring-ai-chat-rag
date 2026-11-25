package dev.havlicek.rag

import org.springframework.ai.document.Document
import org.springframework.ai.reader.markdown.MarkdownDocumentReader
import org.springframework.ai.reader.markdown.config.MarkdownDocumentReaderConfig
import org.springframework.ai.transformer.splitter.TokenTextSplitter
import org.springframework.ai.vectorstore.SearchRequest
import org.springframework.ai.vectorstore.VectorStore
import org.springframework.core.io.support.PathMatchingResourcePatternResolver
import org.springframework.stereotype.Service

data class SearchHit(val source: String?, val score: Double?, val text: String?, val chunkIndex: Int? = null, val topic: String? = null)

@Service
class DocsService(private val vectorStore: VectorStore, private val props: RagProperties) {

    /**
     * Loads every markdown file under classpath:docs, splits into chunks and stores them. Re-ingesting replaces old chunks per file.
     * F3: chunk size/overlap come from rag.chunk-size / rag.chunk-overlap unless [settings] overrides them (chunk experiment),
     * and every chunk gets `topic` + `chunk_index` metadata for filtering and citations.
     */
    fun ingest(
        settings: ChunkSettings = ChunkSettings(props.chunkSize, props.chunkOverlap),
        /** F8: defaults to the active store; the vector store comparison ingests into the other one too */
        store: VectorStore = vectorStore,
    ): Map<String, Int> {
        val splitter = TokenTextSplitter.builder()
            .withChunkSize(settings.chunkSize)
            .withChunkOverlap(settings.chunkOverlap)
            // default min is 350 chars, which would silently merge small chunks back together
            .withMinChunkSizeChars(minOf(350, settings.chunkSize * 2))
            .build()
        val resources = PathMatchingResourcePatternResolver().getResources("classpath:docs/*.md")
        return resources.associate { resource ->
            val name = resource.filename!!
            val config = MarkdownDocumentReaderConfig.builder()
                .withAdditionalMetadata("source", name)
                .withAdditionalMetadata("topic", topicOf(name))
                .build()
            val chunks: List<Document> = splitter.apply(MarkdownDocumentReader(resource, config).get())
                .mapIndexed { i, chunk -> chunk.mutate().metadata(chunk.metadata + ("chunk_index" to i)).build() }
            store.delete("source == '$name'")
            store.add(chunks)
            name to chunks.size
        }
    }

    /** F3: request overrides fall back to rag.chunk-size / rag.chunk-overlap; throws InvalidChunkSettingsException. */
    fun chunkSettings(chunkSize: Int? = null, chunkOverlap: Int? = null) =
        ChunkSettings(chunkSize ?: props.chunkSize, chunkOverlap ?: props.chunkOverlap)

    /** F2: doc files bundled with the app (what /docs/ingest would load). */
    fun availableSources(): List<String> =
        PathMatchingResourcePatternResolver().getResources("classpath:docs/*.md").mapNotNull { it.filename }.sorted()

    /** F2: chunks currently in Qdrant for one source (fine for a small corpus; Qdrant's count API would scale better). */
    fun countChunks(source: String, store: VectorStore = vectorStore): Int =
        store.similaritySearch(
            SearchRequest.builder().query("Kotlin").topK(1000).similarityThresholdAll()
                .filterExpression("source == '${requireSafeSource(source)}'").build()
        ).size

    /** F8: all chunks in a store (one search, so one embedding call; fine for a small corpus). */
    fun countAll(store: VectorStore = vectorStore): Int =
        store.similaritySearch(SearchRequest.builder().query("Kotlin").topK(10_000).similarityThresholdAll().build()).size

    /** F2: removes every chunk of one source from Qdrant. Destructive - only reachable through a confirmed tool call. */
    fun deleteSource(source: String): Int {
        val count = countChunks(source)
        vectorStore.delete("source == '${requireSafeSource(source)}'")
        return count
    }

    fun search(query: String, topK: Int = 4): List<SearchHit> =
        vectorStore.similaritySearch(SearchRequest.builder().query(query).topK(topK).build())
            .map { SearchHit(it.metadata["source"]?.toString(), it.score, it.text, (it.metadata["chunk_index"] as? Number)?.toInt(), it.metadata["topic"]?.toString()) }

    companion object {
        private val SAFE_SOURCE = Regex("[A-Za-z0-9_-]+\\.md")

        /** F3: a tiny catalog standing in for real document metadata (in the Study Guide app: user tags / subject). */
        val TOPICS = mapOf(
            "coroutines.md" to "concurrency",
            "data-classes.md" to "classes",
            "sealed-classes.md" to "classes",
            "null-safety.md" to "type-system",
        )

        fun topicOf(source: String): String = TOPICS[source] ?: "general"

        /** Values end up inside a filter expression, so only plain doc file names are allowed. */
        fun requireSafeSource(source: String): String =
            source.also { require(SAFE_SOURCE.matches(it)) { "Invalid source '$it', expected a file name like coroutines.md" } }
    }
}
