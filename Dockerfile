FROM eclipse-temurin:17-jdk-noble AS build
RUN apt-get update && apt-get install -y --no-install-recommends git python3 \
    && rm -rf /var/lib/apt/lists/*
WORKDIR /src
COPY . .
ARG SOURCE_COMMIT
ARG SOURCE_TREE
ARG SOURCE_DIRTY
ENV SOURCE_COMMIT=${SOURCE_COMMIT} SOURCE_TREE=${SOURCE_TREE} SOURCE_DIRTY=${SOURCE_DIRTY}
RUN python3 scripts/release.py

FROM eclipse-temurin:17-jdk-noble
RUN apt-get update && apt-get install -y --no-install-recommends git ripgrep curl \
    && rm -rf /var/lib/apt/lists/* \
    && useradd --create-home --uid 10001 agent \
    && mkdir -p /app/logs /app/workspace/data \
    && chown -R agent:agent /app
WORKDIR /app
COPY --from=build /src/dist/*.jar /app/cc-agent-java.jar
USER agent
EXPOSE 8080
HEALTHCHECK --interval=30s --timeout=5s --start-period=30s \
  CMD curl --fail --silent http://127.0.0.1:8080/agent.html > /dev/null || exit 1
ENTRYPOINT ["java", "-jar", "/app/cc-agent-java.jar"]
