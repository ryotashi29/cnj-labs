# AgentCore Runtime 用のコンテナ（Spring AI 版）。決まった口（POST /invocations、GET /ping、ポート 8080）は
# spring-ai-agentcore-runtime-starter が用意する。Runtime は linux/arm64 だけに対応している。

FROM --platform=linux/arm64 amazoncorretto:25 AS build
# amazoncorretto:25 には xargs がなく、gradlew が起動できないので findutils を入れる
RUN dnf install -y findutils && dnf clean all
WORKDIR /workspace
COPY gradlew settings.gradle build.gradle ./
COPY gradle ./gradle
RUN ./gradlew --no-daemon dependencies > /dev/null 2>&1 || true
COPY src ./src
RUN ./gradlew --no-daemon clean bootJar -x test

# トレースを CloudWatch に送るための ADOT の Java エージェントと、Spring AI AgentCore SDK の拡張
# （CloudWatch の GenAI Observability のダッシュボードに必要な属性を付け、session.id を span に載せる）
ARG ADOT_VERSION=v2.32.0
ARG AGENTCORE_OTEL_EXTENSION_VERSION=2.3.0
RUN curl -fsSL -o /workspace/aws-opentelemetry-agent.jar \
        "https://github.com/aws-observability/aws-otel-java-instrumentation/releases/download/${ADOT_VERSION}/aws-opentelemetry-agent.jar" \
 && curl -fsSL -o /workspace/agentcore-otel-extension.jar \
        "https://repo1.maven.org/maven2/org/springaicommunity/spring-ai-agentcore-otel-extension/${AGENTCORE_OTEL_EXTENSION_VERSION}/spring-ai-agentcore-otel-extension-${AGENTCORE_OTEL_EXTENSION_VERSION}.jar"

FROM --platform=linux/arm64 amazoncorretto:25-alpine
RUN addgroup -S app && adduser -S app -G app
WORKDIR /app
COPY --from=build /workspace/build/libs/spring-ai-bedrock-agentcore-spring-0.0.1.jar app.jar
COPY --from=build /workspace/aws-opentelemetry-agent.jar /workspace/agentcore-otel-extension.jar ./
# 拡張は AGENT_OBSERVABILITY_ENABLED=true（AgentCore Runtime が入れる）のときだけ働く
ENV JAVA_TOOL_OPTIONS="-javaagent:/app/aws-opentelemetry-agent.jar" \
    OTEL_JAVAAGENT_EXTENSIONS=/app/agentcore-otel-extension.jar
USER app
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
