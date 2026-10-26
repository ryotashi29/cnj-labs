"""犬連れのお出かけ先を提案するエージェント（Strands Agents 版）。Spring AI 版の OutingAgent と同じ振る舞いにする。

- メモリ：``AGENTCORE_MEMORY_ID`` があれば、AgentCore Memory のセッションマネージャー
  （``AgentCoreMemorySessionManager``）で会話を保存・復元する。なければ（手元の実行）、
  Strands の Agent の中（プロセスのメモリ）にしか残らない
- ツール：地名の検索を、AgentCore Gateway のツールとして呼ぶ
"""

from __future__ import annotations

import json
import os

from strands import Agent, tool
from strands.models import BedrockModel

from . import gateway
from .contract import AgentAnswer

MODEL_ID = os.environ.get("BEDROCK_MODEL_ID", "jp.anthropic.claude-haiku-5-5")
REGION = os.environ.get("AWS_REGION", "ap-northeast-1")
MEMORY_ID = os.environ.get("AGENTCORE_MEMORY_ID", "")

#: 長期メモリを読む名前空間。CDK で Memory の戦略に付けた名前空間と揃える。
#: Spring AI 版は Memory の設定を AWS から読んで自動で決める（auto-discovery）が、Strands 版は自分で書く
MEMORY_NAMESPACES = [
    ns for ns in os.environ.get(
        "AGENTCORE_MEMORY_NAMESPACES", "/preferences/{actorId},/summaries/{actorId}/{sessionId}"
    ).split(",") if ns
]

#: ツールを呼べる回数の上限（Spring AI 版の GeocodingTool.MAX_CALLS と揃える）
MAX_TOOL_CALLS = 2

SYSTEM_PROMPT = """\
あなたは、犬連れの家族にお出かけ先を提案するアシスタントです。
ユーザーの条件（地域・犬と一緒に入れるか・屋外か屋内か・目的など）を汲み取り、具体的な施設名を提案します。

手順:
1. 候補を 4 件考え、resolve_place_coordinates にまとめて渡す。
2. ツールが返した座標と住所を使って回答する。座標が取れなかった候補は外し、3 件まで選ぶ。

制約:
- 緯度・経度は自分で作らず、必ずツールが返した値を使う。
- 犬と一緒に入れるかが不確かな場合は、理由にそう書く。断定しない。
- それまでの会話の条件（地域など）は引き継ぐ。
"""

# BedrockModel は boto3 のクライアントを作るので、プロセスの中で使い回す。
# Agent は会話の履歴を持つので、リクエストごとに作る
_model: BedrockModel | None = None


def build_model() -> BedrockModel:
    global _model
    if _model is None:
        _model = BedrockModel(model_id=MODEL_ID, region_name=REGION)
    return _model


def build_geocoding_tool(authorization: str):
    """1 リクエスト分のツールを作る。呼んだ回数と利用者のトークンを閉じ込める。"""
    state = {"calls": 0}

    @tool
    def resolve_place_coordinates(queries: list[str]) -> str:
        """複数の地名・施設名から、座標（緯度・経度）と住所をまとめて調べる。日本国内が対象。

        座標は必ずこのツールの結果を使うこと。呼べる回数には上限があるので、調べたい候補は 1 回でまとめて渡すこと。

        Args:
            queries: 地名・施設名のリスト（例: ["代々木公園", "昭和記念公園"]）
        """
        if state["calls"] >= MAX_TOOL_CALLS:
            return json.dumps(
                {"error": "これ以上は検索できません。取得済みの候補だけで回答してください。"}, ensure_ascii=False
            )
        state["calls"] += 1
        return json.dumps(gateway.resolve(queries or [], authorization), ensure_ascii=False)

    return resolve_place_coordinates


def build_session_manager(actor_id: str, session_id: str):
    """AgentCore Memory のセッションマネージャーを作る。Memory がなければ None。"""
    if not MEMORY_ID:
        return None
    # 実行時にしか要らないので、ここで import する（手元のテストで AWS の設定を要らなくするため）
    from bedrock_agentcore.memory.integrations.strands.config import AgentCoreMemoryConfig, RetrievalConfig
    from bedrock_agentcore.memory.integrations.strands.session_manager import AgentCoreMemorySessionManager

    config = AgentCoreMemoryConfig(
        memory_id=MEMORY_ID,
        session_id=session_id,
        actor_id=actor_id,
        retrieval_config={ns: RetrievalConfig(top_k=5) for ns in MEMORY_NAMESPACES},
    )
    return AgentCoreMemorySessionManager(config, region_name=REGION)


def chat(message: str, actor_id: str, session_id: str, authorization: str) -> AgentAnswer:
    """1 回の会話。"""
    agent = Agent(
        model=build_model(),
        tools=[build_geocoding_tool(authorization)],
        system_prompt=SYSTEM_PROMPT,
        session_manager=build_session_manager(actor_id, session_id),
    )
    result = agent(message, structured_output_model=AgentAnswer)
    answer = result.structured_output
    return answer if isinstance(answer, AgentAnswer) else AgentAnswer()
