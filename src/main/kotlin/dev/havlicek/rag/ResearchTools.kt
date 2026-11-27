package dev.havlicek.rag

import ai.koog.agents.core.tools.annotations.LLMDescription
import ai.koog.agents.core.tools.reflect.ToolSet
import org.springframework.ai.tool.annotation.ToolParam
import org.springframework.stereotype.Component
import ai.koog.agents.core.tools.annotations.Tool as KoogTool
import org.springframework.ai.tool.annotation.Tool as SpringTool

/**
 * F7: the research assistant's tools, written ONCE and annotated for BOTH frameworks:
 * - Koog reads @KoogTool + @LLMDescription (ToolSet reflection) -> POST /kotlin/agent/research
 * - Spring AI reads @SpringTool + @ToolParam (ToolCallbacks.from) -> POST /kotlin/chat/tools
 * Same names, same descriptions, same code, so /kotlin/agent/compare compares the orchestration, not the tools.
 * Tools never throw: errors come back as "Error: ..." text so the model can recover (e.g. fix an expression).
 */
@Component
@LLMDescription("Research tools: local Kotlin docs search, a calculator and an allow-listed docs web fetcher")
class ResearchTools(
    private val docsService: DocsService,
    private val web: WebPageFetcher,
) : ToolSet {

    @KoogTool
    @SpringTool(description = SEARCH_DOCS)
    @LLMDescription(SEARCH_DOCS)
    fun searchKotlinDocs(
        @ToolParam(description = "Search query in plain English, e.g. 'sealed class subclasses location'")
        @LLMDescription("Search query in plain English, e.g. 'sealed class subclasses location'") query: String,
    ): String {
        val hits = docsService.search(query, 4)
        if (hits.isEmpty()) return "No matching docs found."
        return hits.joinToString("\n---\n") { "[source: ${it.source}]\n${it.text}" }
    }

    @KoogTool
    @SpringTool(description = CALCULATE)
    @LLMDescription(CALCULATE)
    fun calculate(
        @ToolParam(description = "Arithmetic expression, e.g. '(1200 * 0.15) / 3' or 'sqrt(2) ^ 2'")
        @LLMDescription("Arithmetic expression, e.g. '(1200 * 0.15) / 3' or 'sqrt(2) ^ 2'") expression: String,
    ): String = try {
        "$expression = ${Calculator.format(Calculator.evaluate(expression))}"
    } catch (e: IllegalArgumentException) {
        "Error: ${e.message}"
    } catch (e: ArithmeticException) {
        "Error: ${e.message}"
    }

    @KoogTool
    @SpringTool(description = FETCH_PAGE)
    @LLMDescription(FETCH_PAGE)
    fun fetchWebPage(
        @ToolParam(description = "Full https URL on an allowed docs site")
        @LLMDescription("Full https URL on an allowed docs site") url: String,
    ): String = web.fetchText(url)

    companion object {
        const val SEARCH_DOCS = "Semantic search over the local Kotlin docs (data classes, sealed classes, null safety, " +
            "coroutines). Returns doc chunks with their source file name. Prefer this for Kotlin language questions."
        const val CALCULATE = "Evaluates an arithmetic expression exactly: + - * / % ^, parentheses, " +
            "sqrt(x), abs(x), round(x), pi, e. Use it for ANY arithmetic instead of computing in your head."
        const val FETCH_PAGE = "Fetches a documentation web page and returns its visible text (truncated). " +
            "Only https pages on kotlinlang.org, docs.spring.io, docs.koog.ai and api.koog.ai are allowed. " +
            "Use it for Spring or Koog questions or when the local Kotlin docs do not cover something."
    }
}
