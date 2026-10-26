"""AgentCore Gateway（MCP）のツールを呼ぶ。Spring AI 版の GatewayClient と同じ役割。

Gateway に渡すトークンはリクエストごとに違う（呼び出した利用者の ID トークン）ので、
MCP のクライアントはリクエストごとに作って閉じる。
"""

from __future__ import annotations

import json
import logging
import os
import uuid
from typing import Any

from strands.tools.mcp import MCPClient

logger = logging.getLogger(__name__)

#: Gateway の MCP の口。CDK が環境変数で渡す
GATEWAY_URL = os.environ.get("GATEWAY_URL", "")

#: Gateway は「ターゲット名___ツール名」の形でツールを公開する
TOOL_NAME = "geocoding___resolve_place_coordinates"


def payload(result: Any) -> dict[str, Any]:
    """ツールの結果から、地名ごとの候補を取り出す。

    Lambda ターゲットの戻り値は ``structuredContent`` に入ることも、テキストの JSON で返ることもあるので、両方を受ける。
    """
    structured = result.get("structuredContent")
    if isinstance(structured, dict):
        # 値が object でないと {"result": ...} で包まれることがある
        inner = structured.get("result", structured)
        if isinstance(inner, dict):
            return inner
    for block in result.get("content") or []:
        text = block.get("text") if isinstance(block, dict) else None
        if not text:
            continue
        try:
            data = json.loads(text)
        except ValueError:
            continue
        if isinstance(data, dict):
            return data
    return {}


def resolve(queries: list[str], authorization: str) -> dict[str, Any]:
    """複数の地名の座標をまとめて引く。失敗したときは空の dict（例外は投げない）。"""
    if not GATEWAY_URL:
        logger.warning("GATEWAY_URL が設定されていないので、ツールを呼べません")
        return {}
    headers = {"Authorization": authorization} if authorization else {}
    try:
        with MCPClient(url=GATEWAY_URL, headers=headers) as client:
            result = client.call_tool_sync(
                tool_use_id=str(uuid.uuid4()), name=TOOL_NAME, arguments={"queries": queries}
            )
    except Exception:  # noqa: BLE001 - 接続の失敗で会話全体を落とさない
        logger.warning("Gateway に接続できませんでした", exc_info=True)
        return {}
    if result.get("status") == "error" or result.get("isError"):
        logger.warning("Gateway のツールの呼び出しが失敗しました: tool=%s", TOOL_NAME)
        return {}
    return payload(result)
