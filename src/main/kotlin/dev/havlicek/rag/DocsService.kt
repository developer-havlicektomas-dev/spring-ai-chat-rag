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

    fun search(query: String, topK: Int = 4): List<SearchHit> =
        vectorStore.similaritySearch(SearchRequest.builder().query(query).topK(topK).build())
            .map { SearchHit(it.metadata["source"]?.toString(), it.score, it.text) }
}
