FROM gradle:9.3.1-jdk21 AS build
WORKDIR /workspace
COPY gradle gradle
COPY gradlew build.gradle settings.gradle ./
RUN ./gradlew --no-daemon dependencies
COPY src src
COPY bpmn bpmn
RUN ./gradlew --no-daemon bootJar

FROM eclipse-temurin:21-jre
ARG TARGETARCH
RUN apt-get update \
    && apt-get install -y --no-install-recommends ca-certificates curl xz-utils \
    && case "$TARGETARCH" in arm64) node_arch=arm64 ;; amd64) node_arch=x64 ;; *) exit 1 ;; esac \
    && curl --fail --silent --show-error --location "https://nodejs.org/dist/v22.14.0/node-v22.14.0-linux-${node_arch}.tar.xz" --output /tmp/node.tar.xz \
    && tar -xJf /tmp/node.tar.xz -C /usr/local --strip-components=1 \
    && npm install --global @openai/codex \
    && useradd --create-home --uid 10001 worker \
    && rm -rf /var/lib/apt/lists/* /tmp/node.tar.xz
WORKDIR /app
COPY --from=build /workspace/build/libs/agents-orchestrator-0.1.0.jar app.jar
COPY projects projects
USER worker
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
