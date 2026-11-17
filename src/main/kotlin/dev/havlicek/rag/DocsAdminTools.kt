package dev.havlicek.rag

import org.slf4j.LoggerFactory
import org.springframework.ai.tool.annotation.Tool
import org.springframework.ai.tool.annotation.ToolParam
import org.springframework.stereotype.Component

/**
 * F2: Spring AI tools for the docs "ops assistant" (POST /kotlin/ops/ask).
 * Reading tools run straight away; the destructive one is marked requiresConfirmation (new in Spring AI 2.1),
 * so the tool-calling loop pauses and hands the call back to us instead of running it.
 */
@Component
class DocsAdminTools(private val docsService: DocsService) {

    private val log = LoggerFactory.getLogger(javaClass)

    @Tool(description = "List the Kotlin doc files that can be indexed, with how many chunks of each are in the vector store right now.")
    fun listDocSources(): Map<String, Int> =
        docsService.availableSources().associateWith { docsService.countChunks(it) }

    @Tool(
        description = "Permanently delete all chunks of one doc file (e.g. coroutines.md) from the vector store.",
        requiresConfirmation = true,
    )
    fun deleteDocSource(@ToolParam(description = "Doc file name, e.g. coroutines.md") source: String): String {
        val removed = docsService.deleteSource(source)
        log.info("Confirmed tool call: deleted {} chunks of {}", removed, source)
        return "Deleted $removed chunks of $source."
    }

    @Tool(
        description = "Re-index all bundled Kotlin doc files into the vector store (replaces existing chunks per file).",
        requiresConfirmation = true,
    )
    fun reindexDocs(): Map<String, Int> = docsService.ingest().also { log.info("Confirmed tool call: re-indexed {}", it) }
}
