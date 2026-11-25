package dev.havlicek.rag

import org.springframework.ai.document.Document
import org.springframework.ai.embedding.Embedding
import org.springframework.ai.embedding.EmbeddingModel
import org.springframework.ai.embedding.EmbeddingRequest
import org.springframework.ai.embedding.EmbeddingResponse

/** Offline embeddings for tests: one dimension per keyword (count of occurrences), plus a bias so no vector is zero. */
class KeywordEmbeddingModel(private val keywords: List<String> = listOf("sealed", "data", "coroutine", "null", "class", "copy", "launch", "safe")) : EmbeddingModel {
    var calls = 0
        private set

    fun vector(text: String): FloatArray {
        val lower = text.lowercase()
        return FloatArray(keywords.size + 1) { i -> if (i == keywords.size) 0.1f else Regex(keywords[i]).findAll(lower).count().toFloat() }
    }

    override fun call(request: EmbeddingRequest): EmbeddingResponse {
        calls++
        return EmbeddingResponse(request.instructions.mapIndexed { i, text -> Embedding(vector(text), i) })
    }

    override fun embed(document: Document): FloatArray = vector(document.text.orEmpty())
    override fun dimensions(): Int = keywords.size + 1
}

fun chunk(source: String, index: Int, text: String) =
    Document.builder().text(text).metadata(mapOf("source" to source, "chunk_index" to index)).build()

val SAMPLE_CHUNKS = listOf(
    chunk("sealed-classes.md", 0, "A sealed class restricts its subclasses. sealed sealed"),
    chunk("data-classes.md", 0, "A data class gets copy, equals and hashCode. data class copy"),
    chunk("coroutines.md", 0, "launch starts a coroutine. coroutine launch"),
    chunk("null-safety.md", 0, "Use safe calls on null values. null safe"),
)
