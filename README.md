# spring-ai-kotlin-rag

A small learning project: RAG over Kotlin docs with Spring Boot 4.1.2, Spring AI 2.0.2, Kotlin 2.3.21, OpenAI and Qdrant.

## Prerequisites
- JDK 21
- Qdrant on localhost (REST on 6333, gRPC on 6334). Spring AI uses gRPC on 6334.
- `export OPENAI_API_KEY=sk-...`. You need it even to start the app, because creating the Qdrant collection makes one embedding call to read the vector size.

Optional overrides: `CHAT_MODEL` (default `gpt-4o-mini`), `QDRANT_HOST`, `QDRANT_GRPC_PORT`.

## Run
```bash
./gradlew build      # runs offline; tests mock the services
./gradlew bootRun
```

## Try it
```bash
curl -X POST localhost:8080/kotlin/docs/ingest
curl "localhost:8080/kotlin/docs/search?q=how%20do%20I%20avoid%20NullPointerException&k=3"
curl -X POST localhost:8080/kotlin/chat/ask -H 'Content-Type: application/json' \
     -d '{"question":"When should I use async instead of launch?"}'
```

## How it works
- `src/main/resources/docs/*.md`: sample docs. `DocsService.ingest()` reads them with `MarkdownDocumentReader`, splits them with `TokenTextSplitter` and stores them in the Qdrant collection `kotlin-docs`. Running ingest again replaces each file's chunks.
- `AiConfig` builds a `ChatClient` with `QuestionAnswerAdvisor`. It pulls the top 4 matching chunks into the prompt.
- `KotlinController` exposes the three endpoints.
