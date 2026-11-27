package dev.havlicek.rag

import org.springframework.ai.openai.OpenAiChatOptions

/**
 * F5: one answer model the app may use (rag.eval.models) and its list price in USD per 1M tokens.
 * A null price means "not verified" -> the cost column shows "unknown" instead of a guess.
 */
data class EvalModel(
    val name: String,
    val inputPer1m: Double? = null,
    val outputPer1m: Double? = null,
    /** reasoning models only (gpt-5*, o*): none/minimal/low/medium/high; null = provider default */
    val reasoningEffort: String? = null,
)

class InvalidModelException(message: String) : RuntimeException(message)

/**
 * F5: the configured answer-model catalogue: validates names coming from HTTP / Gradle (an allow-list, so nobody can
 * run gpt-5.5-pro through POST /kotlin/evals/run), builds the per-request ChatOptions override and does the cost math.
 */
class ModelCatalog(models: List<EvalModel>) {
    private val byName = models.associateBy { it.name }
    val names: List<String> get() = byName.keys.toList()

    fun require(name: String): EvalModel =
        byName[name.trim()] ?: throw InvalidModelException("unknown model '$name', add it to rag.eval.models (known: ${byName.keys})")

    /** "a, b,,a" -> [a, b]; every name must be configured. */
    fun parse(names: Collection<String>?): List<String> =
        names.orEmpty().flatMap { it.split(',') }.map { it.trim() }.filter { it.isNotEmpty() }.distinct().map { require(it).name }

    /** USD for one call from its real prompt/completion usage (completion includes reasoning tokens); null = price unknown. */
    fun costUsd(model: String, promptTokens: Int, completionTokens: Int): Double? {
        val m = byName[model] ?: return null
        val input = m.inputPer1m ?: return null
        val output = m.outputPer1m ?: return null
        return (promptTokens * input + completionTokens * output) / 1_000_000
    }

    /**
     * F10: like [costUsd] but also for the dated snapshot the provider reports ("gpt-4o-mini-2024-07-18" ->
     * "gpt-4o-mini"); the longest configured name that prefixes the snapshot wins.
     */
    fun costUsdResolved(model: String, promptTokens: Int, completionTokens: Int): Double? =
        resolve(model)?.let { costUsd(it, promptTokens, completionTokens) }

    fun resolve(model: String): String? = byName[model]?.name
        ?: byName.keys.filter { model.startsWith("$it-") && SNAPSHOT.matches(model.removePrefix("$it-")) }.maxByOrNull { it.length }

    /** Per-request override for the RAG answer call only (query rewrite, decision steps and judges keep their models). */
    fun chatOptions(name: String, temperature: Double? = null): OpenAiChatOptions.Builder = answerOptions(require(name), temperature)

    companion object {
        private val SNAPSHOT = Regex("\\d{4}-\\d{2}-\\d{2}")

        /** gpt-5 family and o-series are reasoning models: they 400 on any temperature other than the default 1. */
        fun isReasoning(model: String): Boolean = model.startsWith("gpt-5") || Regex("^o\\d").containsMatchIn(model)

        fun answerOptions(model: EvalModel, temperature: Double? = null): OpenAiChatOptions.Builder =
            OpenAiChatOptions.builder().model(model.name).apply {
                if (temperature != null && !isReasoning(model.name)) temperature(temperature)
                if (model.reasoningEffort != null && isReasoning(model.name)) reasoningEffort(model.reasoningEffort)
            }
    }
}
