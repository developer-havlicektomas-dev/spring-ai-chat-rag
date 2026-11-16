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
)
