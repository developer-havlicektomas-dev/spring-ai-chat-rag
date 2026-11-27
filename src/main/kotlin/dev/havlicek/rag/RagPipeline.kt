package dev.havlicek.rag

import org.slf4j.LoggerFactory
import org.springframework.ai.decision.DecisionModel
import org.springframework.ai.decision.DecisionPrompt
import org.springframework.ai.decision.question.YesNoQuestion
import org.springframework.ai.document.Document
import org.springframework.ai.chat.prompt.PromptTemplate
import org.springframework.ai.rag.Query
import org.springframework.ai.rag.generation.augmentation.QueryAugmenter
import org.springframework.ai.rag.postretrieval.document.DecisionDocumentFilter
import org.springframework.ai.rag.postretrieval.document.DecisionDocumentReranker
import org.springframework.ai.rag.postretrieval.document.DocumentPostProcessor

/** What one post-processing step did to the chunk list (returned in /chat/ask as `pipeline`). */
data class StepTrace(val step: String, val inCount: Int, val outCount: Int, val ms: Long, val kept: List<String>)

/**
 * F3: keys we read/write in the RAG request context.
 * RetrievalAugmentationAdvisor builds one mutable HashMap per request and passes that same map to the query
 * transformers, post-processors and (later) the ChatClientResponse, so it doubles as a per-request scratchpad.
 */
object RagContext {
    /** Advisor params: per-request on/off overrides for the decision steps. */
    const val RERANK = "rag.rerank"
    const val DECISION_FILTER = "rag.decision-filter"

    /** Written by [FollowUpQueryTransformer]: the standalone rewrite of a follow-up question. */
    const val REWRITTEN_QUERY = "rag.rewritten-query"
    const val TRACE = "rag.trace"
    const val SUFFICIENCY = "rag.sufficiency"

    fun put(query: Query, key: String, value: Any) {
        try {
            query.context()[key] = value
        } catch (_: UnsupportedOperationException) {
            // immutable context (e.g. Query("text") in tests) - nothing to record into
        }
    }

    @Suppress("UNCHECKED_CAST")
    fun trace(context: Map<String, Any?>): List<StepTrace> = context[TRACE] as? List<StepTrace> ?: emptyList()

    fun addTrace(query: Query, step: StepTrace) = put(query, TRACE, trace(query.context()) + step)

    /**
     * Post-processors get the ORIGINAL user text, not the rewritten follow-up (see RetrievalAugmentationAdvisor.before).
     * "Can its subclasses live in another file?" is unjudgeable on its own, so decision steps see the rewrite instead.
     */
    fun effectiveQuery(query: Query): Query =
        (query.context()[REWRITTEN_QUERY] as? String)?.let { query.mutate().text(it).build() } ?: query
}

/** Short id used in traces and citations: "coroutines.md#2". */
fun Document.chunkLabel(): String = "${metadata["source"] ?: "?"}#${(metadata["chunk_index"] as? Number)?.toInt() ?: "?"}"

/** Label plus what a decision step wrote: "sealed-classes.md#2 rerank=0.93" / "data-classes.md#3 INCLUDED". */
fun Document.traceLabel(): String = listOfNotNull(
    chunkLabel(),
    (metadata[DecisionDocumentReranker.SCORE_METADATA_KEY] as? Number)?.let { "rerank=%.2f".format(it.toDouble()) },
    metadata[DecisionDocumentFilter.CLASSIFICATION_METADATA_KEY]?.toString(),
).joinToString(" ")

/**
 * F3: wraps a post-processor so it can be switched on/off by config (default) or per request (advisor param),
 * judges against the rewritten follow-up query, and records count/latency/kept chunks.
 */
class PipelineStep(
    val name: String,
    private val delegate: DocumentPostProcessor,
    private val enabledByDefault: Boolean,
    private val overrideKey: String? = null,
) : DocumentPostProcessor {
    override fun process(query: Query, documents: List<Document>): List<Document> {
        val enabled = overrideKey?.let { query.context()[it] as? Boolean } ?: enabledByDefault
        if (!enabled || documents.isEmpty()) return documents
        val started = System.nanoTime()
        val result = delegate.process(RagContext.effectiveQuery(query), documents)
        val ms = (System.nanoTime() - started) / 1_000_000
        RagContext.addTrace(query, StepTrace(name, documents.size, result.size, ms, result.map { it.traceLabel() }))
        return result
    }
}

/**
 * F3: one DecisionModel yes/no call over the whole (post-processed) context: "can the query be answered from this?".
 * Below [minProbability] it returns no documents, so the augmenter switches to the labeled general-knowledge fallback.
 * Fails open (keeps the documents) if the decision call errors or is refused.
 */
class SufficiencyGate(private val decisionModel: DecisionModel, private val minProbability: Double) : DocumentPostProcessor {
    private val log = LoggerFactory.getLogger(javaClass)

    override fun process(query: Query, documents: List<Document>): List<Document> {
        if (documents.isEmpty()) return documents
        val prompt = DecisionPrompt.builder()
            .fields(mapOf<String, Any?>("query" to query.text(), "context" to documents.joinToString("\n\n") { it.text.orEmpty() }))
            .question(QUESTION_NAME, QUESTION)
            .build()
        val probability = try {
            decisionModel.call(prompt).getYesNo(QUESTION_NAME).probability
        } catch (e: RuntimeException) {
            log.warn("Sufficiency decision failed, keeping {} documents: {}", documents.size, e.message)
            return documents
        }
        RagContext.put(query, RagContext.SUFFICIENCY, probability)
        log.info("Context sufficiency for '{}': {}", query.text(), probability)
        return if (probability >= minProbability) documents else emptyList()
    }

    companion object {
        const val QUESTION_NAME = "context_is_sufficient"
        val QUESTION: YesNoQuestion = YesNoQuestion.builder()
            .text("Does the `context` contain enough information to answer the `query` without outside knowledge?")
            .whenYes("The answer to the query can be stated from the context alone")
            .whenNo("The context is off-topic or misses facts needed to answer the query")
            .build()
    }
}

/**
 * F3: the stock ContextualQueryAugmenter renders its empty-context template WITHOUT the query, which is fine for a
 * fixed refusal but useless for a fallback answer. With [fallback] set, empty context renders that template with {query}.
 */
class GroundedOrFallbackAugmenter(
    private val grounded: QueryAugmenter,
    private val fallback: PromptTemplate?,
) : QueryAugmenter {
    override fun augment(query: Query, documents: List<Document>): Query =
        if (documents.isEmpty() && fallback != null) Query(fallback.render(mapOf("query" to query.text())))
        else grounded.augment(query, documents)
}

/** Builds the decision steps in their pipeline order: screen (filter) -> rerank -> sufficiency gate. */
object DecisionSteps {
    fun build(decisionModel: DecisionModel, props: RagProperties): List<DocumentPostProcessor> {
        val timeout = java.time.Duration.ofSeconds(props.decisionTimeoutSeconds)
        return buildList {
            add(PipelineStep(
                "decision-filter",
                DecisionDocumentFilter.builder(decisionModel).failClosed(props.decisionFilter.failClosed).timeout(timeout).build(),
                props.decisionFilter.enabled, RagContext.DECISION_FILTER,
            ))
            add(PipelineStep(
                "rerank",
                DecisionDocumentReranker.builder(decisionModel).topK(props.rerank.topN).minimumScore(props.rerank.minScore).timeout(timeout).build(),
                props.rerank.enabled, RagContext.RERANK,
            ))
            if (props.sufficiency.enabled) add(PipelineStep("sufficiency-gate", SufficiencyGate(decisionModel, props.sufficiency.minProbability), true))
        }
    }
}
