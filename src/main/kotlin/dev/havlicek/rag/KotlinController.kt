package dev.havlicek.rag

import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Pattern
import jakarta.validation.constraints.Size
import org.springframework.http.MediaType
import org.springframework.http.codec.ServerSentEvent
import org.springframework.web.bind.annotation.*
import reactor.core.publisher.Flux

data class AskRequest(
    @NotBlank @Size(max = 2000) val question: String,
    // Only plain doc file names; keeps the value safe to put into the filter expression.
    @Pattern(regexp = "[A-Za-z0-9_-]+\\.md") val source: String? = null,
)

data class KoogRequest(@NotBlank @Size(max = 2000) val question: String)

@RestController
@RequestMapping("/kotlin")
class KotlinController(
    private val docsService: DocsService,
    private val chatService: ChatService,
    private val koogBridgeService: KoogBridgeService,
    private val koogAgentService: KoogAgentService,
) {
    @PostMapping("/docs/ingest")
    fun ingest(): Map<String, Int> = docsService.ingest()

    @GetMapping("/docs/search")
    fun search(@RequestParam q: String, @RequestParam(defaultValue = "4") k: Int): List<SearchHit> =
        docsService.search(q, k.coerceIn(1, 20))

    @PostMapping("/chat/ask")
    fun ask(@Valid @RequestBody request: AskRequest): RagAnswer =
        chatService.ask(request.question.trim(), request.source)

    /** Streaming variant of /chat/ask (Server-Sent Events). Try: curl -N ... */
    @PostMapping("/chat/stream", produces = [MediaType.TEXT_EVENT_STREAM_VALUE])
    fun stream(@Valid @RequestBody request: AskRequest): Flux<ServerSentEvent<Any>> =
        chatService.stream(request.question.trim(), request.source)

    /** Day 8: same OpenAI model, but called through Koog's PromptExecutor (bridged from Spring AI). */
    @PostMapping("/koog/ask")
    suspend fun koogAsk(@Valid @RequestBody request: KoogRequest): KoogAnswer =
        koogBridgeService.ask(request.question.trim())

    /** Day 9: Koog agent that decides itself when to call the searchKotlinDocs tool. */
    @PostMapping("/agent/ask")
    suspend fun agentAsk(@Valid @RequestBody request: KoogRequest): AgentAnswer =
        koogAgentService.ask(request.question.trim())
}
