plugins {
	kotlin("jvm") version "2.3.21"
	kotlin("plugin.spring") version "2.3.21"
	id("org.springframework.boot") version "4.1.2"
	id("io.spring.dependency-management") version "1.1.7"
}

group = "dev.havlicek"
version = "0.0.1-SNAPSHOT"

java {
	toolchain {
		languageVersion = JavaLanguageVersion.of(21)
	}
}

repositories {
	mavenCentral()
}

// F1: started on 2.1.0-RC2; F5: 2.1.0 GA landed on Maven Central on 2026-11-19, upgraded (no code changes needed).
extra["springAiVersion"] = "2.1.0"
val koogVersion = "1.3.0"
val koogSpringAiVersion = "1.3.0-beta"

dependencies {
	implementation("org.springframework.boot:spring-boot-starter-webmvc")
	implementation("org.springframework.boot:spring-boot-starter-validation")
	// F5: Micrometer + /actuator/metrics (Spring AI publishes gen_ai.client.token.usage per model)
	implementation("org.springframework.boot:spring-boot-starter-actuator")
	implementation("org.jetbrains.kotlin:kotlin-reflect")
	implementation("org.springframework.ai:spring-ai-starter-model-openai")
	implementation("org.springframework.ai:spring-ai-starter-vector-store-qdrant")
	// F1: modular RAG (RetrievalAugmentationAdvisor, query transformers, augmenters)
	implementation("org.springframework.ai:spring-ai-rag")
	// F1: Spring AI 2.1 sessions (replaces ChatMemory) + the advisors that plug them into ChatClient
	implementation("org.springframework.ai:spring-ai-session")
	implementation("org.springframework.ai:spring-ai-session-advisor")
	implementation("org.springframework.ai:spring-ai-markdown-document-reader")
	implementation("tools.jackson.module:jackson-module-kotlin")
	// Koog agents on top of Spring AI: wraps our OpenAI ChatModel into a Koog PromptExecutor
	implementation("ai.koog:koog-agents-jvm:$koogVersion")
	implementation("ai.koog:koog-spring-ai-v2-starter-model-chat:$koogSpringAiVersion")
	// lets Spring MVC controllers be suspend functions
	implementation("org.jetbrains.kotlinx:kotlinx-coroutines-reactor")
	// starts compose.yaml (Qdrant) on bootRun and wires its host/port into Spring AI
	developmentOnly("org.springframework.boot:spring-boot-docker-compose")
	developmentOnly("org.springframework.ai:spring-ai-spring-boot-docker-compose")
	testImplementation("org.springframework.boot:spring-boot-starter-webmvc-test")
	testImplementation("org.jetbrains.kotlin:kotlin-test-junit5")
	testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

dependencyManagement {
	imports {
		mavenBom("org.springframework.ai:spring-ai-bom:${property("springAiVersion")}")
	}
}

kotlin {
	compilerOptions {
		freeCompilerArgs.addAll("-Xjsr305=strict", "-Xannotation-default-target=param-property")
	}
}

tasks.withType<Test> {
	useJUnitPlatform()
}

// F4: ./gradlew evalRag [-PevalVariants=baseline,full] [-PevalLimit=5]
// F5: [-PevalModels=gpt-4o-mini,gpt-4.1-mini] [-PevalDifficulty=hard] -> also build/reports/rag-eval/model-comparison.md
// Boots the app without the web server, ingests docs, runs src/main/resources/evals/golden-qa.json per variant,
// prints a score table and writes build/reports/rag-eval/rag-eval.md. Needs OPENAI_API_KEY and Qdrant
// (on the Mac, compose.yaml starts Qdrant like bootRun does).
tasks.register<org.springframework.boot.gradle.tasks.run.BootRun>("evalRag") {
	group = "verification"
	description = "Runs the RAG golden-set evals and prints a score table"
	val bootRun = tasks.named<org.springframework.boot.gradle.tasks.run.BootRun>("bootRun").get()
	mainClass.set(bootRun.mainClass)
	classpath = bootRun.classpath
	workingDir = projectDir
	args(
		"--spring.main.web-application-type=none",
		"--rag.eval.on-startup=true",
		// comparing models: one variant by default, otherwise the F4 baseline vs full comparison
		"--rag.eval.variants=" + (providers.gradleProperty("evalVariants").orNull
			?: if (providers.gradleProperty("evalModels").isPresent) "baseline" else "baseline,full"),
	)
	providers.gradleProperty("evalLimit").orNull?.let { args("--rag.eval.limit=$it") }
	providers.gradleProperty("evalModels").orNull?.let { args("--rag.eval.compare-models=$it") }
	providers.gradleProperty("evalDifficulty").orNull?.let { args("--rag.eval.difficulty=$it") }
}
