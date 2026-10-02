#!/bin/sh
# k6 を流す前に、計測対象に条件を申告させてログに残す。
#
# 結果だけを持ち帰ると「どの条件を測ったのか」を後から推測することになる。
# /api/diagnostics の応答と k6 の結果が同じログストリームに並んでいれば、その推測が要らなくなる。
set -eu

: "${BASE_URL:?BASE_URL が未設定です}"

echo "== 計測対象の申告 (/api/diagnostics) =="
# k6 のイメージは alpine なので curl はない。wget（busybox）を使う
wget -qO- "$BASE_URL/api/diagnostics" || echo "(diagnostics を取得できませんでした)"
echo

# 引数があればそれを実行する。構成確認のときに、この同じタスク定義で
# 「VPC 内から任意のコマンドを 1 回だけ流す」ことができるようにしてある。
# 確認用に別のタスク定義を用意すると、計測に使う経路と確認に使う経路が別物になる
if [ "$#" -gt 0 ]; then
    exec "$@"
fi

SCRIPT="${K6_SCRIPT:-/scripts/k6/scenario.js}"
echo "== k6 run $SCRIPT (MODE=${MODE:-} LABEL=${LABEL:-}) =="
exec k6 run "$SCRIPT"
