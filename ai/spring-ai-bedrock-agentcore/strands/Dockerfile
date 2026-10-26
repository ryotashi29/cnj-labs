# AgentCore Runtime 用のコンテナ（Strands 版）。決まった口（POST /invocations、GET /ping、ポート 8080）は
# BedrockAgentCoreApp が用意する。Runtime は linux/arm64 だけに対応している。
FROM --platform=linux/arm64 python:3.12-slim

COPY --from=ghcr.io/astral-sh/uv:0.10.4 /uv /uvx /bin/
WORKDIR /app
ENV UV_COMPILE_BYTECODE=1 UV_LINK_MODE=copy UV_PYTHON_DOWNLOADS=0

# 依存だけ先に入れて、ソースを変えたときに入れ直さないようにする
COPY pyproject.toml uv.lock ./
RUN uv sync --frozen --no-install-project --no-dev
COPY src ./src
COPY README.md ./
RUN uv sync --frozen --no-dev

ENV PATH="/app/.venv/bin:$PATH"
EXPOSE 8080
# opentelemetry-instrument で起動すると、ADOT が Strands・boto3 などを自動で計装する。
# 送り先などの設定は、AgentCore Runtime が環境変数で入れる
CMD ["opentelemetry-instrument", "outing-agent"]
