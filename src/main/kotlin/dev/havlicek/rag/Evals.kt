package dev.havlicek.rag

import com.fasterxml.jackson.annotation.JsonValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory
import org.springframework.ai.document.Document
import org.springframework.ai.evaluation.EvaluationRequest
import org.springframework.ai.evaluation.Evaluator
import tools.jackson.module.kotlin.jacksonObjectMapper
import tools.jackson.module.kotlin.readValue
import java.io.InputStream
import kotlin.math.ceil

/** F4: one golden question. [reference] is the answer we expect (written from src/main/resources/docs). */
data class EvalCase(
    val id: String,
    val question: String,
    val reference: String,
    val expectedSources: List<String> = emptyList(),
    /** false = the docs don't cover it; the right behaviour is the labeled general-knowledge fallback */
    val inScope: Boolean = true,
    /** F5: "normal" (the F4 set) or "hard" (multi-hop, traps, precise details, near-miss out-of-scope) */
    val difficulty: String = "normal",
)

object GoldenSet {
    fun load(json: InputStream): List<EvalCase> = jacksonObjectMapper().readValue<List<EvalCase>>(json)
}

class InvalidEvalVariantException(message: String) : RuntimeException(message)

/** F4: on/off combination of the F3 decision post-processors (the sufficiency gate always follows config). */
enum class EvalVariant(@get:JsonValue val wire: String, val rerank: Boolean, val decisionFilter: Boolean) {
    BASELINE("baseline", false, false),
    RERANK("rerank", true, false),
    FILTER("filter", false, true),
    FULL("full", true, true);

    companion object {
        val DEFAULT = listOf(BASELINE, FULL)

        fun parse(names: Collection<String>?): List<EvalVariant> =
            names?.filter { it.isNotBlank() }?.takeIf { it.isNotEmpty() }?.map { name ->
                entries.firstOrNull { it.wire == name.trim().lowercase() }
                    ?: throw InvalidEvalVariantException("unknown variant '$name', use one of ${entries.map { it.wire }}")
            }?.distinct() ?: DEFAULT
    }
}

/** F4: scores for one question under one variant (F5: and one answer model). Null = not applicable. */
data class CaseResult(
    val id: String,
    val variant: EvalVariant,
    val answerType: AnswerType?,
    /** in scope -> grounded answer; out of scope -> labeled fallback */
    val routingOk: Boolean,
    /** an expected source is among the chunks the model saw */
    val retrievalHit: Boolean?,
    /** RelevancyEvaluator: does the answer address the question, given the context? */
    val relevant: Boolean?,
    /** FactCheckingEvaluator(context -> answer): is every claim in the answer backed by the retrieved chunks? */
    val grounded: Boolean?,
    /** FactCheckingEvaluator(answer -> reference): does the answer contain the reference facts? */
    val correct: Boolean?,
    val latencyMs: Long,
    /** time spent in decision filter / reranker / sufficiency gate (part of latencyMs) */
    val decisionMs: Long,
    val promptTokens: Int?,
    val completionTokens: Int?,
    val sources: List<String>,
    val answer: String,
    val error: String? = null,
    // F5
    val model: String = "?",
    val difficulty: String = "normal",
    /** USD of the answer call from its real token usage; null = no usage or unknown price */
    val costUsd: Double? = null,
)

data class VariantSummary(
    val variant: EvalVariant,
    val cases: Int,
    val routing: Double,
    val retrievalHit: Double?,
    val relevancy: Double?,
    val groundedness: Double?,
    val correctness: Double?,
    val avgLatencyMs: Long,
    val p95LatencyMs: Long,
    val avgDecisionMs: Long,
    val avgPromptTokens: Int?,
    val avgCompletionTokens: Int?,
    /** answer call only, mean real per-question cost x 1000; decision + judge calls are not included */
    val estAnswerCostUsdPer1k: Double?,
    val errors: Int,
    // F5
    val model: String = "?",
    val p50LatencyMs: Long = 0,
    /** correctness on difficulty=hard cases only (the F4 set is too easy to separate models) */
    val hardCorrectness: Double? = null,
)

data class EvalReport(
    val answerModel: String,
    val judgeModel: String,
    val summaries: List<VariantSummary>,
    val cases: List<CaseResult>,
    val table: String,
    /** F5: one row per model x variant (markdown) */
    val comparison: String = "",
)

/** Asks the RAG pipeline; separated from ChatService so tests can script it. */
fun interface RagAsker {
    fun ask(question: String, options: AskOptions): AnswerWithContext
}

/**
 * F4: runs the golden set through /chat/ask's pipeline per variant and scores each answer with LLM judges.
 * F5: optionally per answer model too (the judge model stays fixed so scores are comparable across models).
 * Sequential per question (latency stays comparable), the three judge calls run in parallel.
 */
class RagEvalRunner(
    private val cases: List<EvalCase>,
    private val asker: RagAsker,
    private val relevancy: Evaluator,
    private val factCheck: Evaluator,
    private val models: ModelCatalog,
    private val judgeModel: String,
    /** spring.ai.openai.chat.options.model: used (without an override) when no models are requested */
    private val answerModel: String = "?",
    private val forget: (String) -> Unit = {},
) {
    private val log = LoggerFactory.getLogger(javaClass)

    fun run(
        variants: List<EvalVariant> = EvalVariant.DEFAULT,
        limit: Int? = null,
        ids: Set<String>? = null,
        compareModels: List<String> = emptyList(),
        difficulty: String? = null,
    ): EvalReport {
        // validate up front: a typo should be a 400, not 27 failed cases
        val modelNames: List<String?> = models.parse(compareModels).ifEmpty { listOf(null) }
        val selected = cases
            .filter { ids.isNullOrEmpty() || it.id in ids }
            .filter { difficulty == null || it.difficulty == difficulty }
            .let { if (limit != null) it.take(limit) else it }
        val results = modelNames.flatMap { model -> variants.flatMap { variant -> selected.map { evaluate(it, variant, model) } } }
        val summaries = results.groupBy { it.model to it.variant }.map { (key, rs) -> summarize(key.second, rs, key.first) }
        return EvalReport(answerModel, judgeModel, summaries, results, EvalTable.render(summaries, results), ModelComparisonTable.render(summaries))
    }

    /** [model] = null -> no override (the configured default answer model). */
    internal fun evaluate(case: EvalCase, variant: EvalVariant, model: String? = null): CaseResult {
        val label = model ?: answerModel
        val started = System.nanoTime()
        val result = try {
            asker.ask(case.question, AskOptions(rerank = variant.rerank, decisionFilter = variant.decisionFilter, model = model))
        } catch (e: Exception) {
            log.warn("eval {} [{} {}] failed: {}", case.id, label, variant.wire, e.message)
            return CaseResult(
                case.id, variant, null, false, null, null, null, null, ms(started), 0, null, null, emptyList(), "",
                e.message ?: e.javaClass.simpleName, label, case.difficulty,
            )
        }
        val latency = ms(started)
        val answer = result.answer
        forget(answer.conversationId)
        val sources = result.documents.mapNotNull { it.metadata["source"]?.toString() }
        val grounded = answer.answerType == AnswerType.GROUNDED
        val judged = if (case.inScope) judge(case, answer.answer, result.documents.takeIf { grounded }) else Judged()
        log.info("eval {} [{} {}] {} {}ms", case.id, label, variant.wire, answer.answerType.wire, latency)
        return CaseResult(
            id = case.id,
            variant = variant,
            answerType = answer.answerType,
            routingOk = grounded == case.inScope,
            retrievalHit = if (case.inScope) sources.any { it in case.expectedSources } else null,
            relevant = judged.relevant,
            grounded = judged.grounded,
            correct = judged.correct,
            latencyMs = latency,
            decisionMs = answer.pipeline.sumOf { it.ms },
            promptTokens = answer.usage?.promptTokens,
            completionTokens = answer.usage?.completionTokens,
            sources = sources.distinct(),
            answer = answer.answer,
            error = judged.error,
            model = label,
            difficulty = case.difficulty,
            costUsd = answer.usage?.let { models.costUsd(label, it.promptTokens, it.completionTokens) },
        )
    }

    private data class Judged(val relevant: Boolean? = null, val grounded: Boolean? = null, val correct: Boolean? = null, val error: String? = null)

    /** context == null -> the pipeline fell back to general knowledge for an in-scope question: not relevant/grounded. */
    private fun judge(case: EvalCase, answer: String, context: List<Document>?): Judged = runBlocking(Dispatchers.IO) {
        val errors = mutableListOf<String>()
        fun verdict(name: String, call: () -> Boolean): Boolean? = try {
            call()
        } catch (e: Exception) {
            synchronized(errors) { errors += "$name: ${e.message}" }
            null
        }
        val relevant = async {
            if (context == null) false else verdict("relevancy") { relevancy.evaluate(EvaluationRequest(case.question, context, answer)).isPass }
        }
        val grounded = async {
            if (context == null) false else verdict("grounded") { factCheck.evaluate(EvaluationRequest(context, answer)).isPass }
        }
        // reversed fact check: "document" = our answer, "claim" = the reference -> did the answer cover the expected facts?
        // (the other direction would punish every correct extra detail or code sample the model adds)
        val correct = async {
            verdict("correct") { factCheck.evaluate(EvaluationRequest(listOf(Document(answer)), case.reference)).isPass }
        }
        Judged(relevant.await(), grounded.await(), correct.await(), errors.joinToString("; ").ifEmpty { null })
    }

    internal fun summarize(variant: EvalVariant, rs: List<CaseResult>, model: String? = null): VariantSummary {
        val ok = rs.filter { it.error == null || it.answerType != null }
        val latencies = ok.map { it.latencyMs }.sorted()
        val withUsage = ok.filter { it.promptTokens != null }
        val costs = withUsage.map { it.costUsd }
        return VariantSummary(
            variant = variant,
            cases = rs.size,
            routing = rate(rs.map { it.routingOk }) ?: 0.0,
            retrievalHit = rate(rs.map { it.retrievalHit }),
            relevancy = rate(rs.map { it.relevant }),
            groundedness = rate(rs.map { it.grounded }),
            correctness = rate(rs.map { it.correct }),
            avgLatencyMs = latencies.average().orZero(),
            p95LatencyMs = percentile(latencies, 0.95),
            avgDecisionMs = ok.map { it.decisionMs }.average().orZero(),
            avgPromptTokens = withUsage.takeIf { it.isNotEmpty() }?.map { it.promptTokens!! }?.average()?.toInt(),
            avgCompletionTokens = withUsage.takeIf { it.isNotEmpty() }?.map { it.completionTokens ?: 0 }?.average()?.toInt(),
            // any unknown price -> unknown total, never a silently low number
            estAnswerCostUsdPer1k = costs.takeIf { it.isNotEmpty() && null !in it }?.let { c -> 1000 * c.sumOf { it!! } / c.size },
            errors = rs.count { it.error != null },
            model = model ?: rs.firstOrNull()?.model ?: answerModel,
            p50LatencyMs = percentile(latencies, 0.50),
            hardCorrectness = rate(rs.filter { it.difficulty == "hard" }.map { it.correct }),
        )
    }

    private fun rate(values: List<Boolean?>): Double? = values.filterNotNull().takeIf { it.isNotEmpty() }?.let { v -> v.count { it }.toDouble() / v.size }
    private fun Double.orZero(): Long = if (isNaN()) 0 else toLong()
    private fun ms(started: Long) = (System.nanoTime() - started) / 1_000_000

    companion object {
        /** nearest-rank percentile of an ascending list (p50 of [1,2,3,4] = 2, p95 of 20 values = the 19th) */
        fun percentile(sorted: List<Long>, p: Double): Long = sorted.getOrNull(ceil(sorted.size * p).toInt() - 1) ?: 0
    }
}

/** F4: Markdown tables for the console / README / build/reports/rag-eval.md. */
object EvalTable {
    fun render(summaries: List<VariantSummary>, cases: List<CaseResult>): String = buildString {
        appendLine("| model | variant | n | routing | retrieval hit | relevancy | grounded | correct | avg ms | p95 ms | decision ms | tokens in/out | \$ per 1k answers | errors |")
        appendLine("|---|---|---|---|---|---|---|---|---|---|---|---|---|---|")
        summaries.forEach { s ->
            appendLine(
                "| ${s.model} | ${s.variant.wire} | ${s.cases} | ${pct(s.routing)} | ${pct(s.retrievalHit)} | ${pct(s.relevancy)} | ${pct(s.groundedness)} | " +
                    "${pct(s.correctness)} | ${s.avgLatencyMs} | ${s.p95LatencyMs} | ${s.avgDecisionMs} | " +
                    "${s.avgPromptTokens ?: "-"}/${s.avgCompletionTokens ?: "-"} | ${usd(s.estAnswerCostUsdPer1k)} | ${s.errors} |"
            )
        }
        appendLine()
        appendLine("| case | model | variant | type | hit | rel | grd | cor | ms | sources |")
        appendLine("|---|---|---|---|---|---|---|---|---|---|")
        cases.forEach { c ->
            appendLine(
                "| ${c.id} | ${c.model} | ${c.variant.wire} | ${c.answerType?.wire ?: "error"} | ${mark(c.retrievalHit)} | ${mark(c.relevant)} | " +
                    "${mark(c.grounded)} | ${mark(c.correct)} | ${c.latencyMs} | ${c.sources.joinToString(", ")} |"
            )
        }
    }

    internal fun pct(v: Double?) = v?.let { "%.0f%%".format(it * 100) } ?: "-"
    internal fun usd(v: Double?) = v?.let { "%.3f".format(it) } ?: "unknown"
    private fun mark(v: Boolean?) = when (v) { true -> "Y"; false -> "N"; null -> "-" }
}

/** F5: the model comparison table (build/reports/rag-eval/model-comparison.md, README F5 section). */
object ModelComparisonTable {
    fun render(summaries: List<VariantSummary>): String = buildString {
        appendLine("| model | variant | n | routing | relevancy | grounded | correct | correct (hard) | p50 ms | p95 ms | avg tokens in/out | \$ per 1k questions | errors |")
        appendLine("|---|---|---|---|---|---|---|---|---|---|---|---|---|")
        summaries.forEach { s ->
            appendLine(
                "| ${s.model} | ${s.variant.wire} | ${s.cases} | ${EvalTable.pct(s.routing)} | ${EvalTable.pct(s.relevancy)} | " +
                    "${EvalTable.pct(s.groundedness)} | ${EvalTable.pct(s.correctness)} | ${EvalTable.pct(s.hardCorrectness)} | " +
                    "${s.p50LatencyMs} | ${s.p95LatencyMs} | ${s.avgPromptTokens ?: "-"}/${s.avgCompletionTokens ?: "-"} | " +
                    "${EvalTable.usd(s.estAnswerCostUsdPer1k)} | ${s.errors} |"
            )
        }
    }
}
