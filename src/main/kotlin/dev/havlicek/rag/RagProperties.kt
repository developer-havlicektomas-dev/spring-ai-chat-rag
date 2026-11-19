package dev.havlicek.rag

import org.springframework.boot.context.properties.ConfigurationProperties

/** Retrieval + session knobs, tunable in application.yml or via env vars (RAG_TOP_K, RAG_SIMILARITY_THRESHOLD, ...). */
@ConfigurationProperties("rag")
data class RagProperties(
    val topK: Int = 4,
    val similarityThreshold: Double = 0.3,
    /** F1: idle sessions expire after this many minutes. */
    val sessionTtlMinutes: Long = 60,
    /** F1: compact a session once it has more than this many turns... */
    val maxTurns: Int = 10,
    /** ...keeping only the last [keepTurns]. */
    val keepTurns: Int = 6,
    /** F3: TokenTextSplitter chunk size in tokens (800 = Spring AI default). */
    val chunkSize: Int = 800,
    /** F3: tokens repeated between neighbouring chunks (Spring AI 2.1 TokenTextSplitter.withChunkOverlap). */
    val chunkOverlap: Int = 0,
    val rerank: Rerank = Rerank(),
    val decisionFilter: DecisionFilter = DecisionFilter(),
    val sufficiency: Sufficiency = Sufficiency(),
    /** F3: per-batch timeout for DecisionModel calls (reranker, filter, gate). */
    val decisionTimeoutSeconds: Long = 20,
    val eval: EvalSettings = EvalSettings(),
) {
    init {
        ChunkSettings(chunkSize, chunkOverlap) // validates
    }

    /** F3: DecisionDocumentReranker - one yes/no "does this passage answer the query?" call per chunk. */
    data class Rerank(val enabled: Boolean = false, val topN: Int = 3, val minScore: Double = 0.1)

    /** F3: DecisionDocumentFilter - 4 questions per chunk (injection, contradiction, relevance, answer evidence). */
    data class DecisionFilter(val enabled: Boolean = false, val failClosed: Boolean = false)

    /** F3: "is the retrieved context sufficient?" gate; when it says no we answer from general knowledge, labeled. */
    data class Sufficiency(val enabled: Boolean = true, val minProbability: Double = 0.5)
}

/**
 * F4: RAG evals. [judgeModel] grades the answers (use a stronger model than the one answering if you can);
 * prices are USD per 1M tokens of the ANSWER model, used only for the cost column (default: gpt-4o-mini list price).
 */
data class EvalSettings(
    val judgeModel: String = "gpt-4o-mini",
    val inputPricePer1m: Double = 0.15,
    val outputPricePer1m: Double = 0.60,
    /** run the golden set at startup, print the table, write [reportFile] and exit (used by ./gradlew evalRag) */
    val onStartup: Boolean = false,
    val variants: List<String> = emptyList(),
    val limit: Int? = null,
    val reportFile: String = "build/reports/rag-eval/rag-eval.md",
)

class InvalidChunkSettingsException(message: String) : RuntimeException(message)

/** F3: validated splitter settings, shared by config and the ingest endpoint's overrides. */
data class ChunkSettings(val chunkSize: Int, val chunkOverlap: Int) {
    init {
        if (chunkSize !in 16..2000) throw InvalidChunkSettingsException("chunkSize must be 16..2000 tokens, was $chunkSize")
        if (chunkOverlap !in 0..chunkSize / 2) throw InvalidChunkSettingsException("chunkOverlap must be 0..chunkSize/2 (${chunkSize / 2}), was $chunkOverlap")
    }
}
