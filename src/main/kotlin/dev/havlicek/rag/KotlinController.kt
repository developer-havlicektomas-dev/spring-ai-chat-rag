package dev.havlicek.rag

import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Pattern
import jakarta.validation.constraints.Size
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.http.codec.ServerSentEvent
import org.springframework.web.bind.annotation.*
import reactor.core.publisher.Flux

data class AskRequest(
    @NotBlank @Size(max = 2000) val question: String,
    // Only plain doc file names; keeps the value safe to put into the filter expression.
    @Pattern(regexp = "[A-Za-z0-9_-]+\\.md") val source: String? = null,
    // F1: pass the id from a previous answer to continue that conversation
    @Pattern(regexp = "[A-Za-z0-9-]{1,64}") val conversationId: String? = null,
    // F3: richer metadata filter (validated in RetrievalFilter): source IN sources AND topic IN topics
    @Size(max = RetrievalFilter.MAX_VALUES) val sources: List<String>? = null,
    @Size(max = RetrievalFilter.MAX_VALUES) val topics: List<String>? = null,
    // F3: per-request override of rag.rerank.enabled / rag.decision-filter.enabled
    val rerank: Boolean? = null,
    val decisionFilter: Boolean? = null,
    // F5: answer-model override, one of rag.eval.models (validated in ChatService -> 400 otherwise)
    @Size(max = 64) val model: String? = null,
) {
    fun toOptions(userId: String? = null) =
        AskOptions(RetrievalFilter.of(source, sources, topics), conversationId, rerank, decisionFilter, model, userId)
}

data class OpsRequest(
    @NotBlank @Size(max = 2000) val message: String,
    @Pattern(regexp = "[A-Za-z0-9-]{1,64}") val conversationId: String? = null,
)

/** F2: approve or deny the tool calls an /ops/ask answer is waiting on. */
data class ConfirmRequest(
    @Pattern(regexp = "[A-Za-z0-9-]{1,64}") @NotBlank val conversationId: String,
    val approve: Boolean,
    /** Optional: decide only these call ids (the other awaiting calls get the opposite decision). */
    val toolCallIds: List<String>? = null,
    @Size(max = 500) val reason: String? = null,
)

/**
 * F4: which variants to compare (baseline, rerank, filter, full) and optionally only the first [limit] or some [ids].
 * F5: [models] = answer models to compare (each must be in rag.eval.models); [difficulty] = only "hard" cases etc.
 */
data class EvalRequest(
    @Size(max = 4) val variants: List<String>? = null,
    @Size(max = 4) val models: List<String>? = null,
    @Pattern(regexp = "[a-z]{1,16}") val difficulty: String? = null,
    @field:jakarta.validation.constraints.Min(1) val limit: Int? = null,
    val ids: Set<String>? = null,
)

data class KoogRequest(@NotBlank @Size(max = 2000) val question: String)

@RestController
@RequestMapping("/kotlin")
class KotlinController(
    private val docsService: DocsService,
    private val chatService: ChatService,
    private val koogBridgeService: KoogBridgeService,
    private val koogAgentService: KoogAgentService,
    private val koogStudyService: KoogStudyService,
    private val opsAgentService: OpsAgentService,
    private val ragEvalRunner: RagEvalRunner,
) {
    /** F3: optional ?chunkSize=&chunkOverlap= override rag.chunk-size / rag.chunk-overlap for this run (chunk experiment). */
    @PostMapping("/docs/ingest")
    fun ingest(@RequestParam chunkSize: Int?, @RequestParam chunkOverlap: Int?): Map<String, Int> =
        docsService.ingest(docsService.chunkSettings(chunkSize, chunkOverlap))

    @GetMapping("/docs/search")
    fun search(@RequestParam q: String, @RequestParam(defaultValue = "4") k: Int): List<SearchHit> =
        docsService.search(q, k.coerceIn(1, 20))

    /** F8: owner of the conversation = the caller (F10: the API key's user; dev mode: X-User-Id, default rag.default-user). */
    @PostMapping("/chat/ask")
    fun ask(@Valid @RequestBody request: AskRequest, @CallerId user: String?): RagAnswer =
        chatService.ask(request.question.trim(), request.toOptions(validUser(user)))

    /** Streaming variant of /chat/ask (Server-Sent Events). Try: curl -N ... */
    @PostMapping("/chat/stream", produces = [MediaType.TEXT_EVENT_STREAM_VALUE])
    fun stream(@Valid @RequestBody request: AskRequest, @CallerId user: String?): Flux<ServerSentEvent<Any>> =
        chatService.stream(request.question.trim(), request.toOptions(validUser(user)))

    /** F8: the caller's chat sessions (persisted in Postgres), newest first. */
    @GetMapping("/chat/sessions")
    fun sessions(@CallerId user: String?): List<SessionSummary> =
        chatService.sessions(validUser(user))

    /** F1: messages the session currently sends to the model (after compaction). F8: only the owner sees it. */
    @GetMapping("/chat/sessions/{id}")
    fun history(@PathVariable id: String, @CallerId user: String?): ResponseEntity<List<HistoryMessage>> =
        chatService.history(id, validUser(user))?.let { ResponseEntity.ok(it) } ?: ResponseEntity.notFound().build()

    @DeleteMapping("/chat/sessions/{id}")
    fun forget(@PathVariable id: String, @CallerId user: String?): ResponseEntity<Unit> {
        chatService.forget(id, validUser(user))
        return ResponseEntity.noContent().build()
    }

    /** Day 8: same OpenAI model, but called through Koog's PromptExecutor (bridged from Spring AI). */
    @PostMapping("/koog/ask")
    suspend fun koogAsk(@Valid @RequestBody request: KoogRequest): KoogAnswer =
        koogBridgeService.ask(request.question.trim())

    /** Day 9: Koog agent that decides itself when to call the searchKotlinDocs tool. */
    @PostMapping("/agent/ask")
    suspend fun agentAsk(@Valid @RequestBody request: KoogRequest): AgentAnswer =
        koogAgentService.ask(request.question.trim())

    /** F6: custom Koog graph strategy -> typed StudyPack (answer, key points, sources, quiz). */
    @PostMapping("/agent/study")
    suspend fun agentStudy(@Valid @RequestBody request: KoogRequest): StudyAnswer =
        koogStudyService.study(request.question.trim())

    /** F6: the study strategy graph as a Mermaid state diagram (paste into mermaid.live). */
    @GetMapping("/agent/study/graph", produces = [MediaType.TEXT_PLAIN_VALUE])
    fun agentStudyGraph(): String = koogStudyService.mermaid

    /** F2: ops assistant with tools; destructive tools pause for confirmation (status "awaiting_confirmation"). */
    @PostMapping("/ops/ask")
    fun opsAsk(@Valid @RequestBody request: OpsRequest): OpsAnswer =
        opsAgentService.ask(request.message.trim(), request.conversationId)

    @PostMapping("/ops/confirm")
    fun opsConfirm(@Valid @RequestBody request: ConfirmRequest): OpsAnswer =
        opsAgentService.confirm(request.conversationId, request.approve, request.toolCallIds, request.reason?.trim())

    /** F4: run the golden set (slow: several LLM calls per question). Docs must be ingested. */
    @PostMapping("/evals/run")
    fun runEvals(@Valid @RequestBody(required = false) request: EvalRequest?): EvalReport =
        ragEvalRunner.run(
            EvalVariant.parse(request?.variants), request?.limit, request?.ids, request?.models.orEmpty(), request?.difficulty,
        )

    private fun validUser(user: String?) = user?.let(UserDirectory::requireValidId)
}
