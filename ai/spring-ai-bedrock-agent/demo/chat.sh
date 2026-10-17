#!/bin/bash
# エージェントに 1 回話しかける。
#   ./demo/chat.sh <ポート> <会話 ID（新しい会話なら "">）> <メッセージ>
# 例: ./demo/chat.sh 8080 "" "東京で、犬と一緒に行ける広い公園を教えて"
set -euo pipefail
PORT="${1:?ポートを指定してください}"
CID="${2-}"
MSG="${3:?メッセージを指定してください}"
jq -n --arg cid "$CID" --arg msg "$MSG" '{message: $msg} + (if $cid == "" then {} else {conversationId: $cid} end)' \
    | curl -s -X POST "localhost:${PORT}/chat" -H 'Content-Type: application/json' -d @- \
    | jq .
