# spring-ai-kotlin-rag

A small learning project: RAG over Kotlin docs with Spring Boot 4.1.2, Spring AI 2.0.2, Kotlin 2.3.21, OpenAI and Qdrant.

## Prerequisites
- JDK 21
- A running Docker engine (e.g. `colima start`). `bootRun` starts Qdrant from `compose.yaml` via Spring Boot's Docker Compose support (REST/dashboard on 6333, gRPC on 6334; Spring AI uses gRPC) and stops it when the app exits. Data is kept in the `qdrant_storage` volume. Tests skip Docker Compose.
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
# limit retrieval to one doc
curl -X POST localhost:8080/kotlin/chat/ask -H 'Content-Type: application/json' \
     -d '{"question":"How do I start a coroutine?","source":"coroutines.md"}'
```
`/kotlin/chat/ask` returns `{"answer": "...", "sources": [{"source": "coroutines.md", "score": 0.81}]}`.

## How it works
- `src/main/resources/docs/*.md`: sample docs. `DocsService.ingest()` reads them with `MarkdownDocumentReader`, splits them with `TokenTextSplitter` and stores them in the Qdrant collection `kotlin-docs`. Running ingest again replaces each file's chunks.
- `AiConfig` builds a `ChatClient` with `QuestionAnswerAdvisor` (custom prompt in `src/main/resources/prompts/rag-prompt.st`) and `SimpleLoggerAdvisor`. `ChatService` reads the retrieved chunks back from the response context and returns them as `sources`.
- `KotlinController` exposes the three endpoints.

## Tuning notes (Day 6)
- `RAG_TOP_K` (default 4): more chunks give more recall but a longer, costlier prompt and more noise. With 4 small docs, 3 to 5 is plenty.
- `RAG_SIMILARITY_THRESHOLD` (default 0.3, tuned against text-embedding-3-small: on-topic best hits scored ~0.40–0.64, off-topic ~0.15–0.22): chunks below this cosine score are dropped. Too high and nothing is retrieved, so the model answers "I don't know based on the loaded docs."; too low and off-topic chunks sneak in. Check real scores with `/kotlin/docs/search` before picking a value.
- `ADVISOR_LOG_LEVEL=DEBUG` logs the full prompt, including the retrieved chunks, which is the fastest way to see why an answer is wrong.
- The prompt template forbids prior knowledge and gives one fixed refusal sentence, so out-of-scope questions are easy to detect in tests and evals.
- `source` is validated against `[A-Za-z0-9_-]+\.md` because it is put into a filter expression string.

Queries to try: an in-scope one ("What does `?.let` do?"), a cross-doc one ("Can a sealed class be a data class?"), and an out-of-scope one ("How do I configure Ktor routing?"), which should get the refusal.
