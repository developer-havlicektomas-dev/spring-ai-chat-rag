package dev.havlicek.rag

import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.springframework.ai.document.Document
import org.springframework.ai.evaluation.EvaluationResponse
import org.springframework.ai.evaluation.Evaluator
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** F5: model allow-list, per-request options, cost math and the comparison table - no LLM calls. */
class ModelComparisonTest {

    private val catalog = ModelCatalog(
        listOf(
            EvalModel("gpt-4o-mini", 0.15, 0.60),
            EvalModel("gpt-5.4-mini", 0.75, 4.50, reasoningEffort = "low"),
            EvalModel("mystery-model"), // price not verified
        )
    )

    @Test
    fun `model names parse from lists and comma strings, dedupe, and must be allow-listed`() {
        assertEquals(listOf("gpt-4o-mini", "gpt-5.4-mini"), catalog.parse(listOf(" gpt-4o-mini, gpt-5.4-mini", "gpt-4o-mini", "")))
        assertEquals(emptyList<String>(), catalog.parse(null))
        val e = assertFailsWith<InvalidModelException> { catalog.parse(listOf("gpt-5.5-pro")) }
        assertTrue(e.message!!.contains("rag.eval.models"))
    }

    @Test
    fun `reasoning models never get a temperature, others do`() {
        assertTrue(ModelCatalog.isReasoning("gpt-5.4-mini") && ModelCatalog.isReasoning("o3") && !ModelCatalog.isReasoning("gpt-4.1-mini"))
        val classic = catalog.chatOptions("gpt-4o-mini", temperature = 0.0).build()
        assertEquals("gpt-4o-mini", classic.model); assertEquals(0.0, classic.temperature)
        val reasoning = catalog.chatOptions("gpt-5.4-mini", temperature = 0.0).build() as org.springframework.ai.openai.OpenAiChatOptions
        assertEquals("gpt-5.4-mini", reasoning.model); assertNull(reasoning.temperature); assertEquals("low", reasoning.reasoningEffort)
    }

    @Test
    fun `cost comes from real token usage and is unknown without a verified price`() {
        // 2000 in * 0.75/1M + 500 out * 4.50/1M = 0.0015 + 0.00225
        assertEquals(0.00375, catalog.costUsd("gpt-5.4-mini", 2000, 500)!!, 1e-12)
        assertNull(catalog.costUsd("mystery-model", 2000, 500))
        assertNull(catalog.costUsd("not-configured", 1, 1))
    }

    private fun doc() = Document.builder().text("launch returns a Job").metadata(mapOf("source" to "coroutines.md", "chunk_index" to 0)).build()
    private val pass = Evaluator { EvaluationResponse(true, "", emptyMap()) }

    @Test
    fun `runner compares models with per-model usage, cost, percentiles and hard correctness`() {
        val cases = listOf(
            EvalCase("easy", "What does launch return?", "a Job", listOf("coroutines.md")),
            EvalCase("hard", "launch vs async?", "Job vs Deferred", listOf("coroutines.md"), difficulty = "hard"),
        )
        val seen = mutableListOf<String?>()
        val usage = mapOf("gpt-4o-mini" to TokenUsage(1000, 100), "mystery-model" to TokenUsage(800, 50))
        val factCheck = Evaluator { r -> EvaluationResponse(!(r.responseContent == "Job vs Deferred" && seen.last() == "mystery-model"), "", emptyMap()) }
        val runner = RagEvalRunner(cases, RagAsker { _, o ->
            seen += o.model
            AnswerWithContext(RagAnswer("It returns a Job [1].", emptyList(), "c", usage = usage[o.model]), listOf(doc()))
        }, pass, factCheck, catalog, "judge", "default")

        val report = runner.run(listOf(EvalVariant.BASELINE), compareModels = listOf("gpt-4o-mini", "mystery-model"))

        assertEquals<List<String?>>(listOf("gpt-4o-mini", "gpt-4o-mini", "mystery-model", "mystery-model"), seen)
        val (mini, mystery) = report.summaries
        assertEquals("gpt-4o-mini", mini.model); assertEquals(0.21, mini.estAnswerCostUsdPer1k!!, 1e-9)
        assertEquals(1.0, mini.hardCorrectness); assertEquals(0.0, mystery.hardCorrectness)
        assertNull(mystery.estAnswerCostUsdPer1k)
        assertTrue(report.comparison.contains("| gpt-4o-mini | baseline | 2 | 100% | 100% | 100% | 100% | 100% |"), report.comparison)
        assertTrue(report.comparison.contains("| 1000/100 | 0.210 | 0 |"), report.comparison)
        assertTrue(report.comparison.contains("| mystery-model | baseline | 2 |") && report.comparison.contains("| 800/50 | unknown | 0 |"))
        assertTrue(report.cases.all { it.model in setOf("gpt-4o-mini", "mystery-model") })
    }

    @Test
    fun `no models requested runs the default model without an override, difficulty filters cases`() {
        val cases = listOf(EvalCase("a", "q1", "r"), EvalCase("b", "q2", "r", difficulty = "hard"))
        val seen = mutableListOf<String?>()
        val runner = RagEvalRunner(cases, RagAsker { q, o -> seen += "$q/${o.model}"; AnswerWithContext(RagAnswer("x", emptyList(), "c"), listOf(doc())) },
            pass, pass, catalog, "judge", "gpt-4o-mini")
        val report = runner.run(listOf(EvalVariant.BASELINE), difficulty = "hard")
        assertEquals<List<String?>>(listOf("q2/null"), seen)
        assertEquals("gpt-4o-mini", report.summaries.single().model)
        assertFailsWith<InvalidModelException> { runner.run(compareModels = listOf("nope")) }
    }

    @Test
    fun `percentiles are nearest-rank`() {
        assertEquals(2, RagEvalRunner.percentile(listOf(1, 2, 3, 4), 0.5))
        assertEquals(19, RagEvalRunner.percentile((1L..20L).toList(), 0.95))
        assertEquals(0, RagEvalRunner.percentile(emptyList(), 0.5))
    }

    @Test
    fun `token usage metrics are rendered per operation, model and token type`() {
        val registry = SimpleMeterRegistry()
        fun count(model: String, type: String, n: Double) = registry.counter(
            TokenUsageMetrics.NAME, "gen_ai.operation.name", "chat", "gen_ai.request.model", model, "gen_ai.token.type", type,
        ).increment(n)
        count("gpt-4o-mini", "input", 1200.0); count("gpt-4o-mini", "output", 80.0); count("gpt-4.1", "input", 5.0)
        val md = TokenUsageMetrics.render(registry)
        assertTrue(md.contains("| chat | gpt-4.1 | input | 5 |") && md.contains("| chat | gpt-4o-mini | output | 80 |"), md)
        assertTrue(md.indexOf("gpt-4.1 |") < md.indexOf("gpt-4o-mini | input"))
        assertTrue(TokenUsageMetrics.render(null).contains("no `gen_ai.client.token.usage` meters"))
    }
}
