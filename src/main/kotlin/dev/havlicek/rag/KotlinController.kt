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
) {
    fun toOptions() = AskOptions(RetrievalFilter.of(source, sources, topics), conversationId, rerank, decisionFilter)
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

data class KoogRequest(@NotBlank @Size(max = 2000) val question: String)

@RestController
@RequestMapping("/kotlin")
class KotlinController(
    private val docsService: DocsService,
    private val chatService: ChatService,
    private val koogBridgeService: KoogBridgeService,
    private val koogAgentService: KoogAgentService,
    private val opsAgentService: OpsAgentService,
) {
    /** F3: optional ?chunkSize=&chunkOverlap= override rag.chunk-size / rag.chunk-overlap for this run (chunk experiment). */
    @PostMapping("/docs/ingest")
    fun ingest(@RequestParam chunkSize: Int?, @RequestParam chunkOverlap: Int?): Map<String, Int> =
        docsService.ingest(docsService.chunkSettings(chunkSize, chunkOverlap))

    @GetMapping("/docs/search")
    fun search(@RequestParam q: String, @RequestParam(defaultValue = "4") k: Int): List<SearchHit> =
        docsService.search(q, k.coerceIn(1, 20))

    @PostMapping("/chat/ask")
    fun ask(@Valid @RequestBody request: AskRequest): RagAnswer =
        chatService.ask(request.question.trim(), request.toOptions())

    /** Streaming variant of /chat/ask (Server-Sent Events). Try: curl -N ... */
    @PostMapping("/chat/stream", produces = [MediaType.TEXT_EVENT_STREAM_VALUE])
    fun stream(@Valid @RequestBody request: AskRequest): Flux<ServerSentEvent<Any>> =
        chatService.stream(request.question.trim(), request.toOptions())

    /** F1: messages the session currently sends to the model (after compaction). */
    @GetMapping("/chat/sessions/{id}")
    fun history(@PathVariable id: String): ResponseEntity<List<HistoryMessage>> =
        chatService.history(id)?.let { ResponseEntity.ok(it) } ?: ResponseEntity.notFound().build()

    @DeleteMapping("/chat/sessions/{id}")
    fun forget(@PathVariable id: String): ResponseEntity<Unit> {
        chatService.forget(id)
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

    /** F2: ops assistant with tools; destructive tools pause for confirmation (status "awaiting_confirmation"). */
    @PostMapping("/ops/ask")
    fun opsAsk(@Valid @RequestBody request: OpsRequest): OpsAnswer =
        opsAgentService.ask(request.message.trim(), request.conversationId)

    @PostMapping("/ops/confirm")
    fun opsConfirm(@Valid @RequestBody request: ConfirmRequest): OpsAnswer =
        opsAgentService.confirm(request.conversationId, request.approve, request.toolCallIds, request.reason?.trim())

    /** F3: bad metadata filter values or chunk settings. */
    @ExceptionHandler(InvalidFilterException::class, InvalidChunkSettingsException::class)
    fun badRequest(e: RuntimeException): ResponseEntity<Map<String, String?>> =
        ResponseEntity.badRequest().body(mapOf("error" to e.message))

    @ExceptionHandler(ConversationStateException::class)
    fun conflict(e: ConversationStateException): ResponseEntity<Map<String, String?>> =
        ResponseEntity.status(409).body(mapOf("error" to e.message))

    @ExceptionHandler(NoSuchElementException::class)
    fun notFound(e: NoSuchElementException): ResponseEntity<Map<String, String?>> =
        ResponseEntity.status(404).body(mapOf("error" to e.message))
}
