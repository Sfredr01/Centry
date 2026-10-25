# Stage 1: Build the application with Gradle
FROM eclipse-temurin:21-jdk AS builder
WORKDIR /app

# Copy gradle wrapper and build files first to leverage caching
COPY gradlew build.gradle settings.gradle ./
COPY gradle gradle
RUN ./gradlew dependencies --no-daemon

# Copy source code and build bootJar
COPY src src
RUN ./gradlew bootJar -x test --no-daemon

# Stage 2: Lean Runtime Image
FROM eclipse-temurin:21-jre
WORKDIR /app

# Install curl for healthchecks and utilities
RUN apt-get update && apt-get install -y curl && rm -rf /var/lib/apt/lists/*

# Create unprivileged application user
RUN groupadd -r centry && useradd -r -g centry centry

# Copy built artifact from builder stage
COPY --from=builder /app/build/libs/centry.jar app.jar
RUN chown -R centry:centry /app

# SQLite lives in /data, which docker-compose.prod.yml mounts from the host so it survives container recreation.
ENV CENTRY_DATA_DIR=/data
RUN mkdir -p /data && chown centry:centry /data

USER centry

EXPOSE 8080

ENV JAVA_TOOL_OPTIONS="-Xmx384m -Xms128m -XX:+UseG1GC"

HEALTHCHECK --interval=15s --timeout=5s --retries=5 \
  CMD curl -f http://localhost:8080/actuator/health || exit 1

ENTRYPOINT ["java", "-jar", "app.jar"]
