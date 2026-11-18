package dev.havlicek.rag

import org.springframework.ai.vectorstore.filter.Filter
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder

/** Thrown for filter values we refuse to put into a vector store query (mapped to HTTP 400). */
class InvalidFilterException(message: String) : RuntimeException(message)

/**
 * F3: metadata filter for retrieval. All set parts are AND-ed:
 * `source IN [..] AND topic IN [..]`. Built with FilterExpressionBuilder (typed AST),
 * so values are never concatenated into a filter string.
 */
data class RetrievalFilter(val sources: List<String> = emptyList(), val topics: List<String> = emptyList()) {
    init {
        if (sources.size > MAX_VALUES || topics.size > MAX_VALUES) throw InvalidFilterException("At most $MAX_VALUES sources/topics")
        sources.firstOrNull { !SOURCE.matches(it) }?.let { throw InvalidFilterException("Invalid source '$it', expected a file name like coroutines.md") }
        topics.firstOrNull { !TOPIC.matches(it) }?.let { throw InvalidFilterException("Invalid topic '$it', expected lowercase letters and dashes, e.g. type-system") }
    }

    val isEmpty get() = sources.isEmpty() && topics.isEmpty()

    fun toExpression(): Filter.Expression? {
        val b = FilterExpressionBuilder()
        fun anyOf(key: String, values: List<String>) = if (values.size == 1) b.eq(key, values.single()) else b.`in`(key, values)
        return listOfNotNull(
            sources.takeIf { it.isNotEmpty() }?.let { anyOf("source", it) },
            topics.takeIf { it.isNotEmpty() }?.let { anyOf("topic", it) },
        ).reduceOrNull { left, right -> b.and(left, right) }?.build()
    }

    companion object {
        const val MAX_VALUES = 10
        private val SOURCE = Regex("[A-Za-z0-9_-]+\\.md")
        private val TOPIC = Regex("[a-z][a-z-]{0,31}")

        /** Merges the old single `source` field (Day 5) with the F3 list fields; blanks and duplicates are dropped. */
        fun of(source: String? = null, sources: List<String>? = null, topics: List<String>? = null) = RetrievalFilter(
            sources = (listOfNotNull(source) + sources.orEmpty()).map { it.trim() }.filter { it.isNotEmpty() }.distinct(),
            topics = topics.orEmpty().map { it.trim().lowercase() }.filter { it.isNotEmpty() }.distinct(),
        )
    }
}
