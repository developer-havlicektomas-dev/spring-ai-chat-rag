package dev.havlicek.rag

import org.springframework.ai.document.Document
import org.springframework.ai.rag.postretrieval.document.DecisionDocumentFilter
import org.springframework.ai.rag.postretrieval.document.DecisionDocumentReranker

/** F3: one numbered context chunk; the answer refers to it as [n]. */
data class Citation(
    val n: Int,
    val source: String?,
    val topic: String?,
    val chunkIndex: Int?,
    val chunkId: String?,
    /** vector similarity from Qdrant */
    val score: Double?,
    /** DecisionDocumentReranker probability, when reranking ran */
    val rerankScore: Double?,
    /** DecisionDocumentFilter classification (INCLUDED / CONFLICTING), when the filter ran */
    val classification: String?,
    val snippet: String,
    /** true when the answer actually contains [n] */
    val cited: Boolean,
)

object Citations {
    private val MARKER = Regex("\\[(\\d{1,2})]")

    /** Context block for the prompt; numbering is the list order, the same order [from] uses. */
    fun formatContext(documents: List<Document>): String =
        documents.withIndex().joinToString("\n\n") { (i, doc) -> "[${i + 1}] (${doc.chunkLabel()})\n${doc.text.orEmpty().trim()}" }

    fun citedNumbers(answer: String): Set<Int> = MARKER.findAll(answer).map { it.groupValues[1].toInt() }.toSet()

    fun from(documents: List<Document>, answer: String): List<Citation> {
        val cited = citedNumbers(answer)
        return documents.mapIndexed { i, doc ->
            Citation(
                n = i + 1,
                source = doc.metadata["source"]?.toString(),
                topic = doc.metadata["topic"]?.toString(),
                chunkIndex = (doc.metadata["chunk_index"] as? Number)?.toInt(),
                chunkId = doc.id,
                score = doc.score,
                rerankScore = (doc.metadata[DecisionDocumentReranker.SCORE_METADATA_KEY] as? Number)?.toDouble(),
                classification = doc.metadata[DecisionDocumentFilter.CLASSIFICATION_METADATA_KEY]?.toString(),
                snippet = snippet(doc.text.orEmpty()),
                cited = (i + 1) in cited,
            )
        }
    }

    fun snippet(text: String, max: Int = 160): String {
        val flat = text.replace(Regex("\\s+"), " ").trim()
        return if (flat.length <= max) flat else flat.take(max - 1).trimEnd() + "…"
    }
}
