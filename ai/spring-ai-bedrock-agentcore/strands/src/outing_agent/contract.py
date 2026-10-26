"""エージェントの入出力の形。Spring AI 版の AgentAnswer と同じ形にする。"""

from __future__ import annotations

from pydantic import BaseModel, ConfigDict, Field


class SuggestedPlace(BaseModel):
    """提案する場所 1 件。座標は必ずツールが返した値を使う。"""

    # JSON では camelCase（displayName）で出し入れする（Spring AI 版と揃える）
    model_config = ConfigDict(populate_by_name=True, serialize_by_alias=True)

    name: str = Field(..., description="施設・スポットの名前")
    display_name: str = Field("", alias="displayName", description="住所（ツールが返した値）")
    lat: float = Field(..., description="緯度（必ずツールが返した値を使う）")
    lng: float = Field(..., description="経度（必ずツールが返した値を使う）")
    reason: str = Field("", description="薦める理由（1 文）")


class AgentAnswer(BaseModel):
    """エージェントの回答。"""

    message: str = Field("", description="ユーザー向けの補足（1〜2 文）")
    places: list[SuggestedPlace] = Field(default_factory=list, description="提案する場所（3 件まで）")
