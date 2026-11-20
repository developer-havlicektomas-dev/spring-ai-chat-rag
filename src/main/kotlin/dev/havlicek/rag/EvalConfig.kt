package dev.havlicek.rag

import org.slf4j.LoggerFactory
import org.springframework.ai.chat.client.ChatClient
import org.springframework.ai.chat.evaluation.FactCheckingEvaluator
import org.springframework.ai.chat.evaluation.RelevancyEvaluator
import org.springframework.ai.chat.prompt.ChatOptions
import io.micrometer.core.instrument.MeterRegistry
import org.springframework.beans.factory.ObjectProvider
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.ApplicationRunner
import org.springframework.boot.SpringApplication
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.ApplicationContext
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.io.Resource
import java.io.File
import kotlin.system.exitProcess

@Configuration
class EvalConfig {

    /**
     * F4: judges get their own ChatClient (fresh prototype builder: no RAG/session advisors, no tutor system prompt),
     * so grading never goes through the pipeline under test.
     */
    @Bean
    fun ragEvalRunner(
        builder: ChatClient.Builder,
        chatService: ChatService,
        props: RagProperties,
        @Value("classpath:evals/golden-qa.json") golden: Resource,
        @Value("\${spring.ai.openai.chat.options.model:gpt-4o-mini}") answerModel: String,
        models: ModelCatalog,
    ): RagEvalRunner {
        val judge = builder.defaultOptions(ChatOptions.builder().model(props.eval.judgeModel).temperature(0.0))
        return RagEvalRunner(
            cases = golden.inputStream.use(GoldenSet::load),
            asker = chatService::askWithContext,
            relevancy = RelevancyEvaluator.builder().chatClientBuilder(judge).build(),
            factCheck = FactCheckingEvaluator.builder(judge).build(),
            models = models,
            judgeModel = props.eval.judgeModel,
            answerModel = answerModel,
            forget = chatService::forget, // don't leave 36 throwaway sessions in memory
        )
    }

    /** F4: `./gradlew evalRag` = boot without web, ingest, run the golden set, print + save the table, exit. */
    @Bean
    @ConditionalOnProperty("rag.eval.on-startup", havingValue = "true")
    fun evalOnStartup(
        runner: RagEvalRunner,
        docs: DocsService,
        props: RagProperties,
        meters: ObjectProvider<MeterRegistry>,
        ctx: ApplicationContext,
    ) = ApplicationRunner {
        val log = LoggerFactory.getLogger("RagEval")
        log.info("ingested {}", docs.ingest())
        val e = props.eval
        val report = runner.run(EvalVariant.parse(e.variants), e.limit, compareModels = e.compareModels, difficulty = e.difficulty)
        File(e.reportFile).apply { parentFile?.mkdirs() }.writeText(report.markdown())
        println("\n" + report.markdown())
        println("report written to ${File(e.reportFile).absolutePath}")
        // F5: the comparison plus what Micrometer counted (answer + rewrite + decision + judge + embedding calls)
        val comparison = report.comparisonMarkdown() + "\n" + TokenUsageMetrics.render(meters.ifAvailable)
        File(e.comparisonReportFile).apply { parentFile?.mkdirs() }.writeText(comparison)
        println("\n" + comparison)
        println("model comparison written to ${File(e.comparisonReportFile).absolutePath}")
        exitProcess(SpringApplication.exit(ctx, { 0 }))
    }
}

fun EvalReport.markdown() = "# RAG eval (default answer model: $answerModel, judge: $judgeModel)\n\n$table"

fun EvalReport.comparisonMarkdown() =
    "# Model comparison (judge: $judgeModel, cost = answer call only, from real token usage)\n\n$comparison"

/**
 * F5: Spring AI's ChatModel/EmbeddingModel observations increment the Micrometer counter `gen_ai.client.token.usage`
 * (tags: gen_ai.operation.name, gen_ai.request.model, gen_ai.response.model, gen_ai.token.type). Same numbers as
 * GET /actuator/metrics/gen_ai.client.token.usage?tag=gen_ai.request.model:gpt-4.1-mini
 */
object TokenUsageMetrics {
    const val NAME = "gen_ai.client.token.usage"

    fun render(registry: MeterRegistry?): String = buildString {
        appendLine("## Micrometer `$NAME` (whole run, all calls)")
        appendLine()
        val counters = registry?.find(NAME)?.counters().orEmpty()
        if (counters.isEmpty()) {
            appendLine("no `$NAME` meters (is an ObservationRegistry/MeterRegistry configured?)")
            return@buildString
        }
        appendLine("| operation | request model | token type | tokens |")
        appendLine("|---|---|---|---|")
        counters
            .map { Triple(it.id.getTag("gen_ai.operation.name") ?: "?", it.id.getTag("gen_ai.request.model") ?: "?", it.id.getTag("gen_ai.token.type") ?: "?") to it.count() }
            .groupBy({ it.first }, { it.second })
            .toSortedMap(compareBy<Triple<String, String, String>> { it.first }.thenBy { it.second }.thenBy { it.third })
            .forEach { (k, v) -> appendLine("| ${k.first} | ${k.second} | ${k.third} | ${v.sum().toLong()} |") }
    }
}
