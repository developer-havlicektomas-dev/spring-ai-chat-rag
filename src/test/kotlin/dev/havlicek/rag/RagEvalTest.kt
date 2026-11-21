package dev.havlicek.rag

import org.springframework.ai.document.Document
import org.springframework.ai.evaluation.EvaluationResponse
import org.springframework.ai.evaluation.Evaluator
import org.springframework.core.io.support.PathMatchingResourcePatternResolver
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** F4 scoring without any LLM: scripted RAG answers and judges. */
class RagEvalTest {

    private val inScope = EvalCase("co-launch", "What does launch return?", "launch returns a Job", listOf("coroutines.md"))
    private val outOfScope = EvalCase("oos", "Capital of France?", "n/a", inScope = false)

    private fun doc(source: String) = Document.builder().text("launch returns a Job").metadata(mapOf("source" to source, "chunk_index" to 0)).build()

    private fun answer(type: AnswerType, docs: List<Document>, rerankMs: Long = 0) = AnswerWithContext(
        RagAnswer(
            answer = "It returns a Job [1].", sources = emptyList(), conversationId = "c", answerType = type,
            pipeline = if (rerankMs > 0) listOf(StepTrace("rerank", 4, 3, rerankMs, emptyList())) else emptyList(),
            usage = TokenUsage(1000, 100),
        ),
        docs,
    )

    private fun judge(pass: Boolean) = Evaluator { EvaluationResponse(pass, "", emptyMap()) }

    private fun runner(asker: RagAsker, relevancy: Evaluator = judge(true), factCheck: Evaluator = judge(true), forgotten: MutableList<String> = mutableListOf()) =
        RagEvalRunner(listOf(inScope, outOfScope), asker, relevancy, factCheck, ModelCatalog(listOf(EvalModel("answer", 0.15, 0.60))), "judge", "answer") { forgotten += it }

    @Test
    fun `grounded in-scope answer with the expected source scores everything`() {
        val r = runner({ _, _ -> answer(AnswerType.GROUNDED, listOf(doc("coroutines.md"))) }).evaluate(inScope, EvalVariant.BASELINE)
        assertTrue(r.routingOk && r.retrievalHit!! && r.relevant!! && r.grounded!! && r.correct!!)
        assertEquals(listOf("coroutines.md"), r.sources)
    }

    @Test
    fun `out-of-scope question must fall back and is not judged`() {
        val run = runner({ _, _ -> answer(AnswerType.GENERAL_KNOWLEDGE_FALLBACK, emptyList()) })
        val r = run.evaluate(outOfScope, EvalVariant.BASELINE)
        assertTrue(r.routingOk)
        assertNull(r.retrievalHit); assertNull(r.relevant); assertNull(r.grounded); assertNull(r.correct)
    }

    @Test
    fun `in-scope question that fell back fails routing, relevancy and groundedness`() {
        val r = runner({ _, _ -> answer(AnswerType.GENERAL_KNOWLEDGE_FALLBACK, emptyList()) }).evaluate(inScope, EvalVariant.BASELINE)
        assertEquals(false, r.routingOk); assertEquals(false, r.retrievalHit); assertEquals(false, r.relevant); assertEquals(false, r.grounded)
    }

    @Test
    fun `variant switches decision steps, sessions are cleaned up, judge errors are recorded not thrown`() {
        val seen = mutableListOf<AskOptions>()
        val forgotten = mutableListOf<String>()
        val broken = Evaluator { throw IllegalStateException("judge down") }
        val r = runner({ _, o -> seen += o; answer(AnswerType.GROUNDED, listOf(doc("coroutines.md")), rerankMs = 300) }, factCheck = broken, forgotten = forgotten)
            .evaluate(inScope, EvalVariant.FULL)
        assertEquals(AskOptions(rerank = true, decisionFilter = true), seen.single())
        assertEquals(listOf("c"), forgotten)
        assertEquals(300, r.decisionMs)
        assertNull(r.grounded)
        assertTrue(r.error!!.contains("judge down"))
    }

    @Test
    fun `summary rates ignore not-applicable scores and estimate answer cost`() {
        val report = runner({ q, _ ->
            if (q.startsWith("Capital")) answer(AnswerType.GENERAL_KNOWLEDGE_FALLBACK, emptyList())
            else answer(AnswerType.GROUNDED, listOf(doc("null-safety.md"))) // wrong source
        }).run(listOf(EvalVariant.BASELINE))
        val s = report.summaries.single()
        assertEquals(2, s.cases)
        assertEquals(1.0, s.routing)
        assertEquals(0.0, s.retrievalHit) // only the in-scope case counts
        assertEquals(1.0, s.relevancy)
        // 1000 in * 0.15/1M + 100 out * 0.60/1M = 0.00021 per answer -> 0.21 per 1k
        assertEquals(0.21, s.estAnswerCostUsdPer1k!!, 1e-9)
        assertTrue(report.table.contains("| answer | baseline | 2 | 100% | 0% |"))
    }

    @Test
    fun `variant names parse, default to baseline vs full, reject unknown`() {
        assertEquals(EvalVariant.DEFAULT, EvalVariant.parse(null))
        assertEquals(listOf(EvalVariant.RERANK, EvalVariant.FILTER), EvalVariant.parse(listOf(" Rerank", "filter", "rerank")))
        assertFailsWith<InvalidEvalVariantException> { EvalVariant.parse(listOf("turbo")) }
    }

    @Test
    fun `golden set has unique cases whose expected sources exist, incl hard ones`() {
        val cases = javaClass.getResourceAsStream("/evals/golden-qa.json")!!.use(GoldenSet::load)
        assertTrue(cases.size in 25..35, "size ${cases.size}")
        // F5: hard cases separate the models; some of them must be near-miss out-of-scope questions
        val hard = cases.filter { it.difficulty == "hard" }
        assertTrue(hard.size >= 8 && hard.any { !it.inScope }, "hard ${hard.map { it.id }}")
        assertTrue(cases.all { it.difficulty in setOf("normal", "hard") })
        assertEquals(cases.size, cases.map { it.id }.toSet().size)
        val docs = PathMatchingResourcePatternResolver().getResources("classpath:docs/*.md").map { it.filename }.toSet()
        cases.filter { it.inScope }.forEach { c ->
            assertTrue(c.expectedSources.isNotEmpty() && docs.containsAll(c.expectedSources), "${c.id}: ${c.expectedSources}")
        }
        assertTrue(cases.any { !it.inScope }, "needs out-of-scope cases for the fallback")
    }
}
