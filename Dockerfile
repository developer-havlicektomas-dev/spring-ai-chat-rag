# F10: multi-stage image for the Spring AI + Koog backend (not built on the dev box: no Docker there).
#   docker build -t spring-ai-kotlin-rag .
#   docker run -p 8080:8080 --env-file .env spring-ai-kotlin-rag      (see README "Deploy notes")

# --- 1) build: JDK 21 (matches the Gradle toolchain), Gradle wrapper, dependency layer cached separately ---
FROM eclipse-temurin:21-jdk-noble AS build
WORKDIR /src
COPY gradlew settings.gradle.kts build.gradle.kts ./
COPY gradle gradle
RUN ./gradlew --no-daemon dependencies > /dev/null
COPY src src
# tests run in CI / locally (some need Postgres); the image build only packages
RUN ./gradlew --no-daemon bootJar -x test \
 && cp build/libs/*-SNAPSHOT.jar app.jar \
 && java -Djarmode=tools -jar app.jar extract --layers --destination extracted

# --- 2) runtime: JRE only, non-root, Spring Boot layers (dependencies change rarely -> small pushes) ---
FROM eclipse-temurin:21-jre-noble
RUN groupadd --system --gid 10001 app && useradd --system --uid 10001 --gid app --no-create-home app
WORKDIR /app
COPY --from=build /src/extracted/dependencies/ ./
COPY --from=build /src/extracted/spring-boot-loader/ ./
COPY --from=build /src/extracted/snapshot-dependencies/ ./
COPY --from=build /src/extracted/application/ ./
USER 10001:10001
EXPOSE 8080
# container-aware heap; Compose/Docker for local dev is off inside the image (no Docker socket there)
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError" \
    SPRING_DOCKER_COMPOSE_ENABLED=false
# Health checks belong to the platform: GET /actuator/health/liveness and /actuator/health/readiness (public)
ENTRYPOINT ["java", "-jar", "app.jar"]
