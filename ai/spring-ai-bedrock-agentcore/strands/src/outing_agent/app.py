"""AgentCore Runtime の入口。Spring AI 版の AgentInvocation に当たる。

``BedrockAgentCoreApp`` が、AgentCore Runtime の決まった口（``POST /invocations``、``GET /ping``、ポート 8080）を用意する。
"""

from __future__ import annotations

import logging

from bedrock_agentcore.runtime import BedrockAgentCoreApp
from bedrock_agentcore.runtime.context import RequestContext

from .agent import build_model, chat
from .conversation import actor_id

logger = logging.getLogger(__name__)
app = BedrockAgentCoreApp()

# 起動の段階で、作るのに時間がかかる BedrockModel だけ用意しておく
build_model()


def _authorization(context: RequestContext | None) -> str:
    headers = getattr(context, "request_headers", None) or {}
    for key, value in headers.items():
        if key.lower() == "authorization":
            return value or ""
    return ""


@app.entrypoint
def invoke(payload: dict, context: RequestContext | None = None) -> dict:
    """``POST /invocations``。本文は ``{"prompt": "..."}``。

    引数名 ``context`` は変えないこと。SDK は 2 つ目の引数の名前を見て、RequestContext を渡すかを決める。
    """
    authorization = _authorization(context)
    session_id = (getattr(context, "session_id", None) or "default-session")
    logger.info("invoke: sessionId=%s", session_id)
    answer = chat((payload or {}).get("prompt", ""), actor_id(authorization), session_id, authorization)
    return answer.model_dump()


def main() -> None:
    """手元で動かすとき（``uv run outing-agent``）。"""
    app.run(port=8080)


if __name__ == "__main__":
    main()
