package dev.havlicek.rag

import com.fasterxml.jackson.annotation.JsonValue
import org.springframework.ai.chat.client.ChatClient
import org.springframework.ai.chat.client.ChatClientResponse
import org.springframework.ai.document.Document
import org.springframework.ai.openai.OpenAiChatOptions
import org.springframework.ai.rag.advisor.RetrievalAugmentationAdvisor
import org.springframework.ai.rag.retrieval.search.VectorStoreDocumentRetriever
import org.springframework.ai.session.SessionService
import org.springframework.ai.session.advisor.SessionAdvisors
import org.springframework.http.codec.ServerSentEvent
import org.springframework.stereotype.Service
import reactor.core.publisher.Flux
import java.util.concurrent.atomic.AtomicBoolean

data class SourceRef(val source: String?, val score: Double?)

/** F3: how the answer was produced. */
enum class AnswerType(@get:JsonValue val wire: String) {
    /** answered from retrieved chunks, with [n] citations */
    GROUNDED("grounded"),
    /** nothing (sufficient) retrieved, answered from the model's general knowledge, labeled */
    GENERAL_KNOWLEDGE_FALLBACK("general_knowledge_fallback"),
    /** nothing retrieved and the fallback is off (rag.sufficiency.enabled=false) -> fixed refusal */
    NO_CONTEXT("no_context"),
}

data class RagAnswer(
    val answer: String,
    val sources: List<SourceRef>,
    val conversationId: String,
    // F3
    val answerType: AnswerType = AnswerType.GROUNDED,
    val citations: List<Citation> = emptyList(),
    /** probability from the sufficiency gate (null when it did not run) */
    val sufficiency: Double? = null,
    /** which post-processing steps ran, their latency and the chunks that survived */
    val pipeline: List<StepTrace> = emptyList(),
    /** F4: tokens of the answer call (not the decision/judge calls); null if the provider did not report usage */
    val usage: TokenUsage? = null,
    /** F5: model that produced the answer, as reported by the provider (e.g. "gpt-4.1-mini-2025-04-14") */
    val model: String? = null,
)

/** F4: [RagAnswer] plus the post-processed chunks that went into the prompt. */
data class AnswerWithContext(val answer: RagAnswer, val documents: List<Document>)

/** F4: prompt/completion tokens of one chat call. */
data class TokenUsage(val promptTokens: Int, val completionTokens: Int)

data class HistoryMessage(val role: String, val text: String)

/** Token wrapped in JSON: SSE clients strip one leading space after "data:", which would eat spaces between words. */
data class TokenChunk(val text: String)

/** F3: everything about one /chat request besides the question. */
data class AskOptions(
    val filter: RetrievalFilter = RetrievalFilter(),
    val conversationId: String? = null,
    /** null = use rag.rerank.enabled */
    val rerank: Boolean? = null,
    /** null = use rag.decision-filter.enabled */
    val decisionFilter: Boolean? = null,
    /** F5: answer-model override (must be in rag.eval.models); null = spring.ai.openai.chat.options.model */
    val model: String? = null,
    /** F8: owner of the conversation (X-User-Id); null = rag.default-user */
    val userId: String? = null,
)

@Service
class ChatService(
    private val chatClient: ChatClient,
    private val sessionService: SessionService,
    private val props: RagProperties,
    private val models: ModelCatalog,
    private val userSessions: UserSessions,
    /** F10: answer-size cap (rag.limits.chat-max-completion-tokens) */
    private val limits: RequestLimitProperties = RequestLimitProperties(),
) {

    /**
     * Grounded answer with numbered citations. Pass the returned [RagAnswer.conversationId] back
     * to continue the same conversation (F1); omit it to start a new one.
     */
    fun ask(question: String, options: AskOptions = AskOptions()): RagAnswer = askWithContext(question, options).answer

    /** F4: the answer plus the exact chunks the model saw, so evaluators can judge the answer against them. */
    fun askWithContext(question: String, options: AskOptions = AskOptions()): AnswerWithContext {
        // F8: creates the session owned by the caller (or checks ownership) before SessionAdvisors loads it
        val id = userSessions.open(user(options.userId), options.conversationId)
        val response = prompt(question, options, id).call().chatClientResponse()
        val documents = response.documents()
        val type = answerType(documents)
        val text = response.text()
        val answer = RagAnswer(
            answer = label(type) + text,
            sources = documents.map { SourceRef(it.metadata["source"]?.toString(), it.score) },
            conversationId = id,
            answerType = type,
            citations = Citations.from(documents, text),
            sufficiency = response.context()[RagContext.SUFFICIENCY] as? Double,
            pipeline = RagContext.trace(response.context()),
            usage = response.chatResponse()?.metadata?.usage?.let { TokenUsage(it.promptTokens ?: 0, it.completionTokens ?: 0) },
            model = response.chatResponse()?.metadata?.model,
        )
        return AnswerWithContext(answer, documents)
    }

    /**
     * Same RAG + session pipeline, streamed as Server-Sent Events:
     * `conversation` (id), then once retrieval is done `sources`, `answerType` and `citations` (F3; `cited` is
     * unknown while streaming, so always false), many `token` events (the fallback label first), then `done`.
     * Errors become an `error` event instead of a broken connection.
     */
    fun stream(question: String, options: AskOptions = AskOptions()): Flux<ServerSentEvent<Any>> {
        val id = userSessions.open(user(options.userId), options.conversationId)
        val retrievalSent = AtomicBoolean(false)
        val tokens = prompt(question, options, id, streaming = true).stream().chatClientResponse()
            .concatMap { chunk ->
                val events = buildList {
                    if (retrievalSent.compareAndSet(false, true)) {
                        val documents = chunk.documents()
                        val type = answerType(documents)
                        add(event("sources", documents.map { SourceRef(it.metadata["source"]?.toString(), it.score) }))
                        add(event("answerType", mapOf("answerType" to type)))
                        add(event("citations", Citations.from(documents, "")))
                        label(type).takeIf { it.isNotEmpty() }?.let { add(event("token", TokenChunk(it))) }
                    }
                    chunk.text().takeIf { it.isNotEmpty() }?.let { add(event("token", TokenChunk(it))) }
                }
                Flux.fromIterable(events)
            }
        return Flux.just(event("conversation", mapOf("conversationId" to id)))
            .concatWith(tokens)
            .concatWith(Flux.just(event("done", "[DONE]")))
            // F10: classified, safe message (no provider error text); the HTTP status is already 200 once streaming started
            .onErrorResume { e -> Flux.just(event("error", UpstreamErrors.safeMessage(e))) }
            // F10: copy the caller's ThreadLocals (Security principal) into the Reactor context, so the usage
            // observation that finishes on an HTTP client thread is still booked on the right user
            .contextCapture()
    }

    /**
     * What the session currently feeds the model (after compaction), oldest first;
     * null if unknown/expired or owned by another user (F8).
     */
    fun history(conversationId: String, userId: String? = null): List<HistoryMessage>? {
        userSessions.find(user(userId), conversationId) ?: return null
        return sessionService.getActiveMessages(conversationId)
            .map { HistoryMessage(it.messageType.name.lowercase(), it.text.orEmpty()) }
    }

    /** F8: only the owner can delete; unknown ids are a no-op (DELETE stays idempotent). */
    fun forget(conversationId: String, userId: String? = null) = userSessions.delete(user(userId), conversationId)

    /** F8: the caller's sessions, newest first. */
    fun sessions(userId: String? = null): List<SessionSummary> = userSessions.list(user(userId))

    private fun user(userId: String?) = userId ?: props.defaultUser

    internal fun answerType(documents: List<Document>): AnswerType = when {
        documents.isNotEmpty() -> AnswerType.GROUNDED
        props.sufficiency.enabled -> AnswerType.GENERAL_KNOWLEDGE_FALLBACK
        else -> AnswerType.NO_CONTEXT
    }

    private fun prompt(question: String, options: AskOptions, conversationId: String, streaming: Boolean = false) =
        chatClient.prompt()
            .user(question)
            // F5: only the answer call switches model; ChatClient merges these over the default options.
            // F10: answer length cap + token usage in the last stream chunk (stream_options.include_usage) for budgets
            .options(
                (options.model?.let { models.chatOptions(it) } ?: OpenAiChatOptions.builder())
                    .maxCompletionTokens(limits.chatMaxCompletionTokens)
                    .apply { if (streaming) streamUsage(true) }
            )
            .advisors(SessionAdvisors.session(conversationId))
            .advisors { spec ->
                // F3: typed Filter.Expression (FilterExpressionBuilder) instead of string concatenation
                options.filter.toExpression()?.let { spec.param(VectorStoreDocumentRetriever.FILTER_EXPRESSION, it) }
                options.rerank?.let { spec.param(RagContext.RERANK, it) }
                options.decisionFilter?.let { spec.param(RagContext.DECISION_FILTER, it) }
            }

    private fun event(name: String, data: Any): ServerSentEvent<Any> =
        ServerSentEvent.builder(data).event(name).build()

    companion object {
        const val FALLBACK_LABEL = "[General knowledge - not from your docs] "

        fun label(type: AnswerType) = if (type == AnswerType.GENERAL_KNOWLEDGE_FALLBACK) FALLBACK_LABEL else ""
    }
}

private fun ChatClientResponse.text(): String = chatResponse()?.result?.output?.text.orEmpty()

@Suppress("UNCHECKED_CAST")
private fun ChatClientResponse.documents(): List<Document> =
    context()[RetrievalAugmentationAdvisor.DOCUMENT_CONTEXT] as? List<Document> ?: emptyList()
