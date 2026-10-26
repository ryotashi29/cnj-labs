#!/bin/bash
# 評価用の質問（eval-prompts.txt）を、1 問ずつ新しいセッションで Runtime に投げる。
# 使ったセッション ID を標準出力に 1 行ずつ出す（evaluate.py に渡す）。
#   TOKEN=$(./scripts/id-token.sh ...) ./scripts/run-eval-prompts.sh <spring|strands> > sessions-spring.txt
set -euo pipefail
cd "$(dirname "$0")"
TARGET="${1:?spring か strands を指定してください}"
while IFS= read -r prompt; do
    [ -z "$prompt" ] && continue
    session="eval-${TARGET}-$(uuidgen | tr -d - | tr '[:upper:]' '[:lower:]')"
    result=$(./invoke.sh "$TARGET" "$session" "$prompt")
    echo "$(echo "$result" | tail -1) ${prompt}" >&2
    echo "$session"
done < eval-prompts.txt
