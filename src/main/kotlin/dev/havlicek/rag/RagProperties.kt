package dev.havlicek.rag

import org.springframework.boot.context.properties.ConfigurationProperties

/** Retrieval knobs, tunable in application.yml or via env vars (RAG_TOP_K, RAG_SIMILARITY_THRESHOLD). */
@ConfigurationProperties("rag")
data class RagProperties(
    val topK: Int = 4,
    val similarityThreshold: Double = 0.5,
)
