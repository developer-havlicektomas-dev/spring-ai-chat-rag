package dev.havlicek.rag

import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Pattern
import jakarta.validation.constraints.Size
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*
import java.util.UUID

data class ResearchRequest(
    @NotBlank @Size(max = 2000) val question: String,
    @Pattern(regexp = "[A-Za-z0-9-]{1,64}") val conversationId: String? = null,
)

/** F7: Koog research agent (tools + memory + trace) and the Koog vs Spring AI tool-calling comparison. */
@RestController
@RequestMapping("/kotlin")
class KoogResearchController(
    private val koog: KoogResearchService,
    private val spring: SpringToolsService,
) {
    /** Multi-turn: send the returned conversationId again to continue the conversation. */
    @PostMapping("/agent/research")
    suspend fun research(@Valid @RequestBody request: ResearchRequest): ResearchAnswer =
        koog.research(request.question.trim(), request.conversationId)

    @GetMapping("/agent/research/{id}/memory")
    fun memory(@PathVariable id: String): ResponseEntity<List<MemoryMessage>> =
        koog.memory(id)?.let { ResponseEntity.ok(it) } ?: ResponseEntity.notFound().build()

    @DeleteMapping("/agent/research/{id}/memory")
    fun forget(@PathVariable id: String): ResponseEntity<Unit> {
        koog.forget(id)
        return ResponseEntity.noContent().build()
    }

    /** The same tools through plain Spring AI ChatClient tool calling (single turn). */
    @PostMapping("/chat/tools")
    suspend fun springTools(@Valid @RequestBody request: ResearchRequest): EngineRun =
        withContext(Dispatchers.IO) { spring.ask(request.question.trim()) }

    /** Runs both engines one after the other (no shared memory, fresh Koog conversation) and returns them side by side. */
    @PostMapping("/agent/compare")
    suspend fun compare(@Valid @RequestBody request: ResearchRequest): CompareAnswer {
        val question = request.question.trim()
        val koogRun = runCatching {
            val id = "compare-" + UUID.randomUUID()
            koog.research(question, id).also { koog.forget(id) }.toEngineRun()
        }.getOrElse { EngineRun("koog", "", emptyList(), null, null, null, 0, UpstreamErrors.safeMessage(it)) }
        val springRun = runCatching { withContext(Dispatchers.IO) { spring.ask(question) } }
            .getOrElse { EngineRun("spring-ai", "", emptyList(), null, null, null, 0, UpstreamErrors.safeMessage(it)) }
        return CompareAnswer(question, koogRun, springRun)
    }
}

private fun ResearchAnswer.toEngineRun() = EngineRun("koog", answer, toolCalls, llmCalls, inputTokens, outputTokens, latencyMs)
