#!/usr/bin/env bash
# 4 つの並行モデル条件を一巡して計測する。
#
#   mvc-platform     Spring MVC + プラットフォームスレッド
#   mvc-virtual      Spring MVC + Virtual Thread
#   webflux-r2dbc    WebFlux + Reactor + R2DBC
#   webflux-blocking WebFlux のイベントループでブロッキング JDBC を呼ぶ（意図的に間違ったコード）
#
# モードには対策の側も入っている。blocking に対する blocking-isolated（boundedElastic へ載せ替え）、
# pinned に対する pinned-lock（synchronized を ReentrantLock へ置き換え）で、
# どちらも「詰まる側と 1 行しか違わない」ことを見せるために対で用意している。
#
# spring.threads.virtual.enabled は起動時にしか効かず、MVC と WebFlux はそもそも別 jar なので、
# 条件ごとにアプリを起動し直す必要がある。手で起動し直すと条件の取り違えが起きやすいため、
# ビルドから k6 の実行と RSS の採取までをここに固定している。
#
# 使い方:
#   ./k6/sweep.sh                                     # 4 条件を一巡
#   CONDITIONS="mvc-virtual webflux-r2dbc" ./k6/sweep.sh
#   POOL_SIZES="5 10 25" ./k6/sweep.sh
#   SERVER_PREP_STMTS=true CONDITIONS=mvc-virtual MODES=db ./k6/sweep.sh
set -euo pipefail

cd "$(dirname "$0")/.."

CONDITIONS="${CONDITIONS:-mvc-platform mvc-virtual webflux-r2dbc webflux-blocking}"
POOL_SIZES="${POOL_SIZES:-10}"
HOLD_MS="${HOLD_MS:-200}"
BASE_URL="${BASE_URL:-http://localhost:8080}"
JAVA_BIN="${JAVA_BIN:-java}"
# 条件ごとのデフォルトモード。MODES を明示すると全条件でそれを使う
MODES="${MODES:-}"
# サーバ側プリペアドステートメント。AWS では RDS Proxy のセッションピニングを起こす
# 独立変数だが、ローカルには RDS Proxy がないので**ピニングは観測できない**。
# ここで測れるのは「有効にしてもアプリが同じように動くか」までで、
# 多重化が消えることの確認は cdk/scripts/measure-aws.sh で行う
SERVER_PREP_STMTS="${SERVER_PREP_STMTS:-false}"

case "$SERVER_PREP_STMTS" in
    true|false) ;;
    *) echo "SERVER_PREP_STMTS は true か false: $SERVER_PREP_STMTS" >&2; exit 1 ;;
esac

RESULTS_DIR="k6/results"
RSS_SUMMARY="$RESULTS_DIR/rss-summary.md"
mkdir -p "$RESULTS_DIR"

# pinned を JDK 24 以降で測ると「ピニングが起きない」結果になる。JEP 491 でモニタ保持中も
# アンマウントできるようになったため。コードは同じなので結果を見ても気づけず、
# 「ピニングは問題にならない」という誤った結論に直結する。
#
# 計測の主軸は現行 LTS の 25 なので、ここで止めてしまうと通常の一巡が動かない。
# pinned だけ落として、落としたことを目立つように知らせる方針にしている。
# 黙って落とすと「4 条件を測った」と読めてしまうため、必ず表示する
jdk_major=$("$JAVA_BIN" -XshowSettings:properties -version 2>&1 \
    | awk -F'= *' '/java.specification.version/ { print $2 }')
DROP_PINNED=false
if [ "${jdk_major:-0}" -ge 24 ] 2>/dev/null; then
    DROP_PINNED=true
fi

# pinned を落とす。空白区切りのまま扱うので、単語単位で消す。
# pinned-lock も一緒に落とすのは、それが pinned の対策（ReentrantLock への置き換え）であり、
# 効果は「pinned が詰まっていること」との差でしか見えないため。詰まらない JDK で
# 対策だけを測ると、常に健全な結果が出て「効いた」と読めてしまう
drop_pinned_from() {
    printf '%s\n' "$1" | tr ' ' '\n' \
        | grep -v '^pinned$' | grep -v '^pinned-lock$' | grep -v '^$' | tr '\n' ' '
}

command -v k6 >/dev/null || { echo "k6 が見つかりません。brew install k6" >&2; exit 1; }

# ビルドに使う JDK と計測に使う JDK は別に決める。toolchain が 21 なのでコンパイルには
# JDK 21 の実体が必要だが（pinned の対照群のため。README を参照）、計測は JDK 25 で行いたい。
# 同じ変数を使い回すと「JDK 25 で計測したい」だけでビルドが壊れる。
# Gradle 9.7.1 自体はどちらの JDK でも動くので、ここで見ているのは toolchain の解決だけ
GRADLE_JAVA_HOME="${GRADLE_JAVA_HOME:-}"
if [ -z "$GRADLE_JAVA_HOME" ]; then
    gradle_jdk_major=$(java -XshowSettings:properties -version 2>&1 \
        | awk -F'= *' '/java.specification.version/ { print $2 }')
    if [ "${gradle_jdk_major:-0}" -ge 24 ] 2>/dev/null; then
        GRADLE_JAVA_HOME=$(/usr/libexec/java_home -v 21 2>/dev/null || true)
        [ -n "$GRADLE_JAVA_HOME" ] || {
            echo "JDK 21 が見つかりません。toolchain 21 でコンパイルするため JDK 21 が必要です。" >&2
            echo "JDK 21 を入れるか GRADLE_JAVA_HOME を指定してください。" >&2
            exit 1
        }
    fi
fi

echo "==> jar をビルド"
# clean を付けるのは、過去のビルドで別名だった jar が build/libs に残っていると
# 古いバイナリを黙って計測してしまうため（プロジェクト改名時に実際に起きた）
if [ -n "$GRADLE_JAVA_HOME" ]; then
    JAVA_HOME="$GRADLE_JAVA_HOME" ./gradlew -q clean bootJar
else
    ./gradlew -q clean bootJar
fi

# 条件名から「どのモジュールの jar か / 追加の環境変数 / デフォルトのモード」を引く。
# 条件と設定の対応をここ 1 箇所に集めておかないと、結果ファイルのラベルと
# 実際に測った条件がずれる
resolve_condition() {
    case "$1" in
        mvc-platform)
            MODULE=mvc; COND_ENV=(VIRTUAL_THREAD_ENABLED=false); DEFAULT_MODES="nodb db bounded" ;;
        mvc-virtual)
            MODULE=mvc; COND_ENV=(VIRTUAL_THREAD_ENABLED=true)
            DEFAULT_MODES="nodb db bounded pinned pinned-lock" ;;
        webflux-r2dbc)
            MODULE=webflux; COND_ENV=(); DEFAULT_MODES="nodb db bounded" ;;
        # blocking と blocking-isolated は必ず並べて測る。対策の効果は差でしか見えず、
        # isolated 側だけを見ても「速いコード」との区別がつかない
        webflux-blocking)
            MODULE=webflux; COND_ENV=(); DEFAULT_MODES="blocking blocking-isolated" ;;
        *)
            echo "未知の条件: $1" >&2; return 1 ;;
    esac
}

find_jar() {
    local jar
    jar=$(find "$1/build/libs" -name '*.jar' ! -name '*-plain.jar')
    [ "$(printf '%s\n' "$jar" | grep -c .)" = 1 ] || { echo "jar を一意に特定できません: $jar" >&2; return 1; }
    printf '%s' "$jar"
}

APP_PID=""
RSS_PID=""
stop_sampler() {
    if [ -n "$RSS_PID" ] && kill -0 "$RSS_PID" 2>/dev/null; then
        kill "$RSS_PID" 2>/dev/null || true
        wait "$RSS_PID" 2>/dev/null || true
    fi
    RSS_PID=""
}
stop_app() {
    stop_sampler
    if [ -n "$APP_PID" ] && kill -0 "$APP_PID" 2>/dev/null; then
        kill "$APP_PID" 2>/dev/null || true
        wait "$APP_PID" 2>/dev/null || true
    fi
    APP_PID=""
}
# EXIT トラップで終了コードを保存して返す。stop_app が成功すると、そちらの 0 が
# スクリプトの終了コードになってしまう場合がある。途中で落ちた計測が成功に見えるのは危険。
#
# 終了コードだけでは足りないので、最後まで到達したかどうかも別に持つ。
# bash 3.2（macOS のデフォルト）は set -u の中断を終了コード 0 で返すため、
# 変数名の取り違えのような事故が呼び出し側から成功に見えてしまう
COMPLETED=false
finish() {
    local rc=$?
    stop_app
    if [ "$COMPLETED" != true ] && [ "$rc" = 0 ]; then
        echo "!!! 最後まで到達せずに終わりました。上のエラーを読んでください" >&2
        rc=1
    fi
    exit $rc
}
trap finish EXIT
trap 'stop_app; exit 130' INT
trap 'stop_app; exit 143' TERM

wait_for_health() {
    for _ in $(seq 1 60); do
        if curl -fsS "$BASE_URL/actuator/health" >/dev/null 2>&1; then return 0; fi
        sleep 1
    done
    echo "アプリが起動しませんでした" >&2
    return 1
}

# RSS はプロセスの外から測る。/api/diagnostics が返すのはヒープと非ヒープだけで、
# JVM 自身のオーバーヘッド・スレッドスタック・Netty のダイレクトバッファが入らない。
# 3 モデルの差はまさにそこに出るため、ps で継続サンプリングしてピークを取る。
# ps の RSS 単位は macOS / Linux ともに KB
rss_kb() {
    ps -o rss= -p "$1" 2>/dev/null | tr -d ' '
}
start_sampler() {
    local out="$1"
    : > "$out"
    (
        while kill -0 "$APP_PID" 2>/dev/null; do
            rss_kb "$APP_PID" >> "$out"
            sleep 1
        done
    ) &
    RSS_PID=$!
}
peak_rss_mb() {
    awk 'BEGIN { max = 0 } $1 > max { max = $1 } END { printf "%.0f", max / 1024 }' "$1"
}

if [ ! -f "$RSS_SUMMARY" ]; then
    {
        echo '### RSS ピーク（k6 実行中、ps で 1 秒ごとにサンプリング）'
        echo
        echo '| 条件 | プール | モード | prep | 起動直後 MB | ピーク MB |'
        echo '|---|---|---|---|---|---|'
    } > "$RSS_SUMMARY"
fi

for condition in $CONDITIONS; do
    resolve_condition "$condition"
    jar=$(find_jar "$MODULE")

    for pool in $POOL_SIZES; do
        modes="${MODES:-$DEFAULT_MODES}"
        if [ "$DROP_PINNED" = true ] && printf '%s\n' "$modes" | grep -qw pinned; then
            modes=$(drop_pinned_from "$modes")
            # 直後が日本語だと変数名の終わりを取り違えるため ${} で明示する
            echo "!!! pinned を除外しました（JAVA_BIN が JDK ${jdk_major}、JEP 491 で症状が出ない）"
            echo "!!! pinned は JDK 21 で測ってください:"
            echo "!!!   JAVA_BIN=\$(/usr/libexec/java_home -v 21)/bin/java MODES=pinned \\"
            echo "!!!     CONDITIONS=mvc-virtual ./k6/sweep.sh"
        fi
        # pinned だけを指定して JDK 25 で流した場合は、測るものが何も残らない
        if [ -z "${modes// /}" ]; then
            echo "    測るモードが残っていないため $condition をスキップします"
            continue
        fi
        label_base="${condition}-pool${pool}-hold${HOLD_MS}"
        # ピニングを測った結果が、測っていない結果を同じ名前で上書きするのを防ぐ。
        # false のときに接尾辞を付けないのは、既存の結果ファイル名と揃えておくため
        if [ "$SERVER_PREP_STMTS" = true ]; then
            label_base="${label_base}-prepon"
        fi
        echo "==> 起動: $condition (module=$MODULE pool=$pool prep=$SERVER_PREP_STMTS)"

        # webflux 条件は COND_ENV が空配列。set -u のもとで "${COND_ENV[@]}" と書くと
        # 空配列が未定義扱いになって落ちるため、この展開にする必要がある
        env ${COND_ENV[@]+"${COND_ENV[@]}"} POOL_SIZE="$pool" WORKLOAD_PERMITS="$pool" \
            SERVER_PREP_STMTS="$SERVER_PREP_STMTS" METRICS_CONDITION="$condition" \
            "$JAVA_BIN" -jar "$jar" > "$RESULTS_DIR/${label_base}.applog" 2>&1 &
        APP_PID=$!
        wait_for_health

        # 実際の並列度・プール設定・イベントループ本数をプロセス自身に申告させて残す。
        # 後から結果を読むときに条件を推測しなくて済む
        curl -fsS "$BASE_URL/api/diagnostics" > "$RESULTS_DIR/${label_base}.diagnostics.json"
        idle_rss_mb=$(( $(rss_kb "$APP_PID") / 1024 ))

        for mode in $modes; do
            echo "    k6: mode=$mode"
            rss_file="$RESULTS_DIR/${label_base}-${mode}.rss"
            start_sampler "$rss_file"
            BASE_URL="$BASE_URL" MODE="$mode" HOLD_MS="$HOLD_MS" \
                LABEL="${label_base}-${mode}" k6 run k6/scenario.js
            stop_sampler
            echo "| $condition | $pool | $mode | $SERVER_PREP_STMTS | $idle_rss_mb | $(peak_rss_mb "$rss_file") |" \
                >> "$RSS_SUMMARY"
        done

        stop_app
        sleep 2
    done
done

COMPLETED=true
echo "==> 完了。結果は $RESULTS_DIR/、RSS の一覧は $RSS_SUMMARY"
