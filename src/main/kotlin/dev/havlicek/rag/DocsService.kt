package dev.havlicek.rag

import org.springframework.ai.document.Document
import org.springframework.ai.reader.markdown.MarkdownDocumentReader
import org.springframework.ai.reader.markdown.config.MarkdownDocumentReaderConfig
import org.springframework.ai.transformer.splitter.TokenTextSplitter
import org.springframework.ai.vectorstore.SearchRequest
import org.springframework.ai.vectorstore.VectorStore
import org.springframework.core.io.support.PathMatchingResourcePatternResolver
import org.springframework.stereotype.Service

data class SearchHit(val source: String?, val score: Double?, val text: String?)

@Service
class DocsService(private val vectorStore: VectorStore) {

    private val splitter = TokenTextSplitter()

    /** Loads every markdown file under classpath:docs, splits into chunks and stores them. Re-ingesting replaces old chunks per file. */
    fun ingest(): Map<String, Int> {
        val resources = PathMatchingResourcePatternResolver().getResources("classpath:docs/*.md")
        return resources.associate { resource ->
            val name = resource.filename!!
            val config = MarkdownDocumentReaderConfig.builder()
                .withAdditionalMetadata("source", name)
                .build()
            val chunks: List<Document> = splitter.apply(MarkdownDocumentReader(resource, config).get())
            vectorStore.delete("source == '$name'")
            vectorStore.add(chunks)
            name to chunks.size
        }
    }

    /** F2: doc files bundled with the app (what /docs/ingest would load). */
    fun availableSources(): List<String> =
        PathMatchingResourcePatternResolver().getResources("classpath:docs/*.md").mapNotNull { it.filename }.sorted()

    /** F2: chunks currently in Qdrant for one source (fine for a small corpus; Qdrant's count API would scale better). */
    fun countChunks(source: String): Int =
        vectorStore.similaritySearch(
            SearchRequest.builder().query("Kotlin").topK(1000).similarityThresholdAll()
                .filterExpression("source == '${requireSafeSource(source)}'").build()
        ).size

    /** F2: removes every chunk of one source from Qdrant. Destructive - only reachable through a confirmed tool call. */
    fun deleteSource(source: String): Int {
        val count = countChunks(source)
        vectorStore.delete("source == '${requireSafeSource(source)}'")
        return count
    }

    fun search(query: String, topK: Int = 4): List<SearchHit> =
        vectorStore.similaritySearch(SearchRequest.builder().query(query).topK(topK).build())
            .map { SearchHit(it.metadata["source"]?.toString(), it.score, it.text) }

    companion object {
        private val SAFE_SOURCE = Regex("[A-Za-z0-9_-]+\\.md")

        /** Values end up inside a filter expression, so only plain doc file names are allowed. */
        fun requireSafeSource(source: String): String =
            source.also { require(SAFE_SOURCE.matches(it)) { "Invalid source '$it', expected a file name like coroutines.md" } }
    }
}
