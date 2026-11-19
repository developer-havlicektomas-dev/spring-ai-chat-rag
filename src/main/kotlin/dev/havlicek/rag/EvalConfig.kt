package dev.havlicek.rag

import org.slf4j.LoggerFactory
import org.springframework.ai.chat.client.ChatClient
import org.springframework.ai.chat.evaluation.FactCheckingEvaluator
import org.springframework.ai.chat.evaluation.RelevancyEvaluator
import org.springframework.ai.chat.prompt.ChatOptions
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
    ): RagEvalRunner {
        val judge = builder.defaultOptions(ChatOptions.builder().model(props.eval.judgeModel).temperature(0.0))
        return RagEvalRunner(
            cases = golden.inputStream.use(GoldenSet::load),
            asker = chatService::askWithContext,
            relevancy = RelevancyEvaluator.builder().chatClientBuilder(judge).build(),
            factCheck = FactCheckingEvaluator.builder(judge).build(),
            pricing = EvalPricing(props.eval.inputPricePer1m, props.eval.outputPricePer1m),
            judgeModel = props.eval.judgeModel,
            answerModel = answerModel,
            forget = chatService::forget, // don't leave 36 throwaway sessions in memory
        )
    }

    /** F4: `./gradlew evalRag` = boot without web, ingest, run the golden set, print + save the table, exit. */
    @Bean
    @ConditionalOnProperty("rag.eval.on-startup", havingValue = "true")
    fun evalOnStartup(runner: RagEvalRunner, docs: DocsService, props: RagProperties, ctx: ApplicationContext) = ApplicationRunner {
        val log = LoggerFactory.getLogger("RagEval")
        log.info("ingested {}", docs.ingest())
        val report = runner.run(EvalVariant.parse(props.eval.variants), props.eval.limit)
        File(props.eval.reportFile).apply { parentFile?.mkdirs() }.writeText(report.markdown())
        println("\n" + report.markdown())
        println("report written to ${File(props.eval.reportFile).absolutePath}")
        exitProcess(SpringApplication.exit(ctx, { 0 }))
    }
}

fun EvalReport.markdown() = "# RAG eval (answer model: $answerModel, judge: $judgeModel)\n\n$table"
