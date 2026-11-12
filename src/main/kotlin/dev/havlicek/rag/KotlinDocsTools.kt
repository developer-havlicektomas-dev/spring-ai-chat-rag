package dev.havlicek.rag

import ai.koog.agents.core.tools.annotations.LLMDescription
import ai.koog.agents.core.tools.annotations.Tool
import ai.koog.agents.core.tools.reflect.ToolSet
import org.springframework.stereotype.Component

/**
 * Day 9: tools the Koog agent may call. Koog reads the @Tool function signature and the
 * @LLMDescription texts via reflection and sends them to the LLM as a JSON tool schema.
 * The LLM decides if and when to call them; we only provide the capability.
 */
@Component
@LLMDescription("Tools for looking things up in the local Kotlin documentation")
class KotlinDocsTools(private val docsService: DocsService) : ToolSet {

    @Tool
    @LLMDescription(
        "Semantic search over the ingested Kotlin docs (data classes, sealed classes, null safety, coroutines). " +
            "Returns the most relevant doc chunks with their source file name. Use it before answering Kotlin language questions."
    )
    fun searchKotlinDocs(
        @LLMDescription("Search query in plain English, e.g. 'sealed class subclasses location'") query: String,
        @LLMDescription("How many chunks to return, 1 to 8") topK: Int = 4,
    ): String {
        val hits = docsService.search(query, topK.coerceIn(1, 8))
        if (hits.isEmpty()) return "No matching docs found."
        // Plain text keeps the tool result small and easy for the model to cite.
        return hits.joinToString("\n---\n") { "[source: ${it.source}]\n${it.text}" }
    }
}
