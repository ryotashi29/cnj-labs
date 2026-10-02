#!/usr/bin/env bash
# 「数の限られたスレッド（キャリアスレッド・イベントループ）をブロックすると、無関係なリクエストまで巻き込まれる」ことを測る。
#
# sweep.sh が測るのは各エンドポイント単体のスループットだが、それだけでは
# webflux-blocking の本当の問題が見えない。プールサイズが小さいと、正しい R2DBC 実装も
# 間違ったブロッキング実装も同じ上限に張り付くため、rps だけ見ると差が出ないことがある。
#
# イベントループをブロックすることの害は、そのエンドポイント自身ではなく
# 「DB を触らない別のリクエスト」の遅延に出る。ここを測ると
# Virtual Thread のピニング（キャリアスレッドをブロックする）と同型であることが直接見える。
#
# k6 を使わないのは、必要なのが「負荷中に別エンドポイントの応答時間を見る」ことだけで、
# k6 未インストールの環境でも検証の核が確認できるようにしておきたいため。
#
# 使い方:
#   ./k6/crosstalk.sh                          # webflux 前提。/api/blocking で負荷をかける
#   LOAD_MODE=pinned ./k6/crosstalk.sh         # mvc-virtual に対して同じことを測る
set -euo pipefail

BASE_URL="${BASE_URL:-http://localhost:8080}"
# 負荷側。webflux ならイベントループをブロックする blocking、mvc-virtual ならキャリアスレッドをブロックする pinned
LOAD_MODE="${LOAD_MODE:-blocking}"
LOAD_CONCURRENCY="${LOAD_CONCURRENCY:-30}"
LOAD_HOLD_MS="${LOAD_HOLD_MS:-1000}"
# 観測側は必ず DB を触らないモードにする。DB を触ると
# 遅延の原因がプール待ちなのかスレッド枯渇なのか切り分けられない
PROBE_MODE="${PROBE_MODE:-nodb}"
PROBE_HOLD_MS="${PROBE_HOLD_MS:-10}"
PROBE_SAMPLES="${PROBE_SAMPLES:-10}"

curl -fsS "$BASE_URL/actuator/health" >/dev/null || {
    echo "アプリに接続できません: $BASE_URL" >&2; exit 1
}

# 観測側の平均応答時間。curl の time_total は秒なので ms に直す
probe() {
    local label="$1" total=0 t
    for _ in $(seq 1 "$PROBE_SAMPLES"); do
        t=$(curl -s -o /dev/null -w '%{time_total}' \
            "$BASE_URL/api/$PROBE_MODE?ms=$PROBE_HOLD_MS")
        total=$(echo "$total + $t" | bc -l)
    done
    printf '  %-24s %s(ms=%s) 平均 %.0f ms\n' \
        "$label" "$PROBE_MODE" "$PROBE_HOLD_MS" \
        "$(echo "$total / $PROBE_SAMPLES * 1000" | bc -l)"
}

echo "==> 観測: /api/$PROBE_MODE  負荷: /api/$LOAD_MODE x $LOAD_CONCURRENCY (${LOAD_HOLD_MS}ms)"

probe "負荷なし"

load_pids=()
for _ in $(seq 1 "$LOAD_CONCURRENCY"); do
    curl -s -o /dev/null "$BASE_URL/api/$LOAD_MODE?ms=$LOAD_HOLD_MS" &
    load_pids+=($!)
done
# 負荷が実際にスレッドを掴むまで待つ。ここを省くと立ち上がり途中を測ってしまう
sleep 1
probe "負荷中"

wait "${load_pids[@]}" 2>/dev/null || true
probe "負荷終了後"

echo
echo "負荷中だけ大きく伸びるなら、ブロックされているのは DB 接続ではなくスレッドそのもの。"
