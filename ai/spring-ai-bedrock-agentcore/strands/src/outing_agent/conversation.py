"""AgentCore Memory の actorId（誰の会話か）を決める。Spring AI 版の ConversationIds と同じ決まり。

actorId は、呼び出し側が渡す値ではなく、認証済みの JWT の ``sub`` から取る。セッション ID だけで
会話を引けると、spring-ai-bedrock-agent で確かめた「会話 ID を知っていれば他人の会話を読める」と同じ問題が残るため。
JWT の署名は AgentCore Runtime が検証済みなので、ここでは本文（payload）を読むだけにする。
"""

from __future__ import annotations

import base64
import json
import re

#: 認証のない手元の実行で使う actorId
LOCAL_ACTOR = "local"

_SUB_PATTERN = re.compile(r"^[A-Za-z0-9-]+$")


def actor_id(authorization: str | None) -> str:
    """``Authorization`` から actorId を取る。

    なければ手元の実行とみなして ``LOCAL_ACTOR`` を返す。あるのに読めない場合は、
    ほかの利用者と同じ actorId にならないよう例外にする。
    """
    if not authorization:
        return LOCAL_ACTOR
    if not authorization.lower().startswith("bearer "):
        raise ValueError("Authorization が Bearer トークンではありません")
    parts = authorization[7:].strip().split(".")
    if len(parts) < 2:
        raise ValueError("JWT の形ではありません")
    payload = parts[1] + "=" * (-len(parts[1]) % 4)
    claims = json.loads(base64.urlsafe_b64decode(payload))
    sub = claims.get("sub") if isinstance(claims, dict) else None
    # actorId に使える文字は限られる。Cognito の sub（UUID）の形だけを受ける
    if not isinstance(sub, str) or not _SUB_PATTERN.match(sub):
        raise ValueError("JWT に使える sub がありません")
    return sub
