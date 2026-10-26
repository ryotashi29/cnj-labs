#!/bin/bash
# Runtime を呼ぶ（OAuth の JWT で認証する Runtime は、AWS の署名ではなく HTTPS で直接呼ぶ）。
#   TOKEN=$(./scripts/id-token.sh ...) ./scripts/invoke.sh <spring|strands> <セッション ID> <メッセージ>
# セッション ID は 33 文字以上が必要。同じセッション ID で呼ぶと、同じ会話の続きになる。
set -euo pipefail
cd "$(dirname "$0")"
case "${1:?spring か strands を指定してください}" in
    spring) ARN=$(./stack-output.sh SpringRuntimeArn) ;;
    strands) ARN=$(./stack-output.sh StrandsRuntimeArn) ;;
    *) echo "spring か strands を指定してください" >&2; exit 1 ;;
esac
SESSION_ID="${2:?セッション ID を指定してください}"
MESSAGE="${3:?メッセージを指定してください}"
: "${TOKEN:?TOKEN に ID トークンを入れてください}"
REGION=$(echo "$ARN" | cut -d: -f4)
ENCODED_ARN=$(jq -rn --arg v "$ARN" '$v|@uri')
jq -n --arg p "$MESSAGE" '{prompt: $p}' | curl -sS -X POST \
    "https://bedrock-agentcore.${REGION}.amazonaws.com/runtimes/${ENCODED_ARN}/invocations?qualifier=DEFAULT" \
    -H "Authorization: Bearer ${TOKEN}" \
    -H "Content-Type: application/json" \
    -H "X-Amzn-Bedrock-AgentCore-Runtime-Session-Id: ${SESSION_ID}" \
    -w '\n(HTTP %{http_code}, %{time_total}s)\n' -d @-
