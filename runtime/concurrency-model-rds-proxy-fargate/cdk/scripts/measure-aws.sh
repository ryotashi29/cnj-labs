#!/usr/bin/env bash
# AWS 上で条件を一巡して計測する。k6/sweep.sh の AWS 版。
#
# ローカルの sweep.sh との違いは、条件の切り替えが「プロセスの起動し直し」ではなく
# 「タスク定義の選択 + RunTask の環境変数上書き」になること。タスクサイズは
# タスク定義のプロパティで実行時に変えられないため、サイズだけは定義を選び分ける。
#
# 使い方:
#   ./cdk/scripts/measure-aws.sh verify        # 構成の確認だけ（k6 は流さない）
#   ./cdk/scripts/measure-aws.sh sweep         # 既定の条件で一巡
#   SCRIPT=crosstalk CONDITIONS=webflux-blocking ./cdk/scripts/measure-aws.sh sweep
#   SIZES="cpu256 cpu512 cpu1024 cpu2048" MODES=db ./cdk/scripts/measure-aws.sh sweep
#   SERVER_PREP_STMTS=true CONDITIONS=mvc-virtual MODES=db ./cdk/scripts/measure-aws.sh sweep
#   # 仮説 2 の「問題あり / 対策後」を並べて測る。対策の効果は差でしか見えないので、
#   # 対になる条件を 1 回の実行に入れて、同じ日の同じ Aurora の状態で比べる
#   SCRIPT=crosstalk SIZES=cpu256 \
#     CONDITIONS="webflux-blocking webflux-blocking-isolated mvc-virtual-jdk21 mvc-virtual-jdk21-lock" \
#     ./cdk/scripts/measure-aws.sh sweep
set -euo pipefail

cd "$(dirname "$0")/../.."

# プロファイルは AWS_PROFILE で渡す。未指定なら AWS CLI の既定（default）
PROFILE="${AWS_PROFILE:-default}"
REGION="${AWS_REGION:-ap-northeast-1}"
STACK_NAME="${STACK_NAME:-cnj-concurrency-model}"

CONDITIONS="${CONDITIONS:-mvc-platform mvc-virtual webflux-r2dbc webflux-blocking}"
SIZES="${SIZES:-cpu1024}"
POOL_SIZES="${POOL_SIZES:-10}"
HOLD_MS="${HOLD_MS:-200}"
MODES="${MODES:-}"
# scenario = 段ごとのスループット、crosstalk = 無関係なリクエストへの巻き込み
SCRIPT="${SCRIPT:-scenario}"
# proxy = RDS Proxy 経由、direct = Aurora へ直結（多重化の対照群）
DB_TARGET="${DB_TARGET:-proxy}"
# true にすると Connector/J がサーバ側プリペアドステートメントを使い、RDS Proxy が
# セッションをピニングする。多重化が消えることを示すための独立変数。
# 効くのは JDBC 経路（mvc の全条件と webflux-blocking）だけで、webflux-r2dbc には効かない
SERVER_PREP_STMTS="${SERVER_PREP_STMTS:-false}"
# 生ログの名前に入れる実行時刻。条件名だけだと DB 経路違い・日違いの計測が同じ名前になり、
# 前の生ログを黙って上書きする（2026-09-25 に 3 回踏んだ）
RUN_ID="$(date +%Y%m%d-%H%M)"
# 起動しない・終わらないときに課金し続けるのを防ぐ上限
TASK_START_TIMEOUT_SEC="${TASK_START_TIMEOUT_SEC:-300}"
LOAD_TIMEOUT_SEC="${LOAD_TIMEOUT_SEC:-1500}"

RESULTS_DIR="k6/results"
SUMMARY="$RESULTS_DIR/aws-summary.md"
mkdir -p "$RESULTS_DIR"

command -v jq >/dev/null || { echo "jq が見つかりません。brew install jq" >&2; exit 1; }

aws_cli() {
    aws --profile "$PROFILE" --region "$REGION" "$@"
}

account=$(aws_cli sts get-caller-identity --query Account --output text 2>/dev/null) || {
    echo "プロファイル $PROFILE の資格情報が使えません（期限切れの可能性）。更新してください:" >&2
    echo "  SSO プロファイルなら  : aws sso login --profile $PROFILE" >&2
    echo "  一時資格情報の貼り付けなら: ~/.aws/credentials の [$PROFILE] を差し替え" >&2
    exit 1
}

# どこを測っているかを最初に出す。REGION は AWS_REGION を尊重するので、
# シェルの初期化で別のリージョンが入っていると黙ってそこを見に行き、
# 「スタックが存在しません」という原因のわかりにくいエラーになる（実際に踏んだ）
echo "==> 接続先: アカウント $account / リージョン $REGION / プロファイル $PROFILE"

# ---------------------------------------------------------------- スタック出力

# リソースを名前で決め打ちしないのは、スタックを作り直したときに
# 「古い環境を測っていた」という気づけない事故になるため
outputs_json=$(aws_cli cloudformation describe-stacks --stack-name "$STACK_NAME" \
    --query 'Stacks[0].Outputs' --output json 2>/dev/null) || {
    # ${} で囲むのは変数名の直後が全角文字だから（$REGION） が REGION？ の参照になる）。
    # リージョンの取り違えを知らせるための行がそれ自体で落ちると、原因から最も遠いエラーになる
    echo "スタック $STACK_NAME が見つかりません（アカウント $account / リージョン ${REGION}）。" >&2
    echo "リージョンが意図どおりか確認してください: AWS_REGION=${AWS_REGION:-未設定}" >&2
    exit 1
}

stack_output() {
    local value
    value=$(printf '%s' "$outputs_json" | jq -r --arg key "$1" '.[] | select(.OutputKey == $key) | .OutputValue')
    if [ -z "$value" ] || [ "$value" = null ]; then
        echo "スタック出力 $1 が取れません" >&2
        return 1
    fi
    printf '%s' "$value"
}

CLUSTER=$(stack_output ClusterName)
SUBNETS=$(stack_output TaskSubnetIds)
# public / private どちらの構成でデプロイされたかはスタックが答える。
# ここを決め打ちにすると、構成を変えたときに RunTask が理由のわかりにくい失敗をする
ASSIGN_PUBLIC_IP=$(stack_output TaskAssignPublicIp)
APP_SG=$(stack_output AppSecurityGroupId)
LOAD_SG=$(stack_output LoadSecurityGroupId)
PROXY_ENDPOINT=$(stack_output ProxyEndpoint)
WRITER_ENDPOINT=$(stack_output WriterEndpoint)
# エンドポイントの先頭ラベルがプロキシ名（cnj-cm-proxy.proxy-xxx.rds.amazonaws.com）。
# 名前を決め打ちにしないのは、スタック出力から引く他のリソースと同じ理由で、
# 作り直したときに「古い環境を測っていた」という気づけない事故になるため
PROXY_NAME="${PROXY_ENDPOINT%%.*}"
LOAD_TASK_FAMILY=$(stack_output LoadTaskFamily)
APP_LOG_GROUP=$(stack_output AppLogGroup)
LOAD_LOG_GROUP=$(stack_output LoadLogGroup)

case "$DB_TARGET" in
    proxy) DB_HOST="$PROXY_ENDPOINT" ;;
    direct) DB_HOST="$WRITER_ENDPOINT" ;;
    *) echo "DB_TARGET は proxy か direct: $DB_TARGET" >&2; exit 1 ;;
esac

# 綴りを間違えたまま通すと、Connector/J は未知の値を false と解釈しないどころか
# 接続時に落ちる。落ちなかった場合はもっと悪く、ピニングを測ったつもりで
# 測れていない結果が残るため、ここで弾く
case "$SERVER_PREP_STMTS" in
    true|false) ;;
    *) echo "SERVER_PREP_STMTS は true か false: $SERVER_PREP_STMTS" >&2; exit 1 ;;
esac

# ピニングは RDS Proxy の機能なので、直結では原理的に観測できない。
# 「direct で測ったらピニングが起きなかった」は当たり前の結果で、検証の裏付けにならない
if [ "$SERVER_PREP_STMTS" = true ] && [ "$DB_TARGET" != proxy ]; then
    echo "!!! SERVER_PREP_STMTS=true は DB_TARGET=proxy でないと意味がありません（ピニングは"
    echo "!!! RDS Proxy の挙動）。直結の結果は多重化の対照群としてのみ読んでください。"
fi

# ---------------------------------------------------------------- 条件の対応表

# 条件名から「どのタスク定義か / 追加の環境変数 / 既定のモード / crosstalk の負荷側 /
# 枯渇を測るメトリクス名」を引く。ローカルの sweep.sh と同じ責務。対応をここ 1 箇所に
# 集めておかないと、結果のラベルと実際に測った条件がずれる。
#
# LAG_METRIC が条件ごとに違うのは、少数しかない資源そのものが違うため。mvc 系はキャリアスレッド、
# webflux 系はイベントループで、どちらも「並列実行数のために用意されたスレッド」にあたる。
# 名前を揃えて 1 つにしてしまうと、グラフを見たときにどちらの資源の話なのか分からなくなる
resolve_condition() {
    case "$1" in
        mvc-platform)
            VARIANT=mvc-jdk25; COND_ENV=(VIRTUAL_THREAD_ENABLED=false)
            DEFAULT_MODES="nodb db bounded"; CROSSTALK_LOAD=db
            LAG_METRIC=CarrierLagMillis ;;
        mvc-virtual)
            VARIANT=mvc-jdk25; COND_ENV=(VIRTUAL_THREAD_ENABLED=true)
            DEFAULT_MODES="nodb db bounded"; CROSSTALK_LOAD=pinned
            LAG_METRIC=CarrierLagMillis ;;
        mvc-virtual-jdk21)
            VARIANT=mvc-jdk21; COND_ENV=(VIRTUAL_THREAD_ENABLED=true)
            DEFAULT_MODES="pinned"; CROSSTALK_LOAD=pinned
            LAG_METRIC=CarrierLagMillis ;;
        # 仮説 2 の対策のうち「隔離」側。pinned と同じ負荷を ReentrantLock で受けるだけの差で、
        # JDK を上げずにキャリアスレッドが解放されることを示す対照群
        mvc-virtual-jdk21-lock)
            VARIANT=mvc-jdk21; COND_ENV=(VIRTUAL_THREAD_ENABLED=true)
            DEFAULT_MODES="pinned-lock"; CROSSTALK_LOAD=pinned-lock
            LAG_METRIC=CarrierLagMillis ;;
        webflux-r2dbc)
            VARIANT=webflux-jdk25; COND_ENV=()
            DEFAULT_MODES="nodb db bounded"; CROSSTALK_LOAD=db
            LAG_METRIC=EventLoopLagMillis ;;
        webflux-blocking)
            VARIANT=webflux-jdk25; COND_ENV=()
            DEFAULT_MODES="blocking"; CROSSTALK_LOAD=blocking
            LAG_METRIC=EventLoopLagMillis ;;
        # 同じブロッキング呼び出しを boundedElastic に載せ替えただけの条件。
        # 「載せ替えられるのは書いた本人だけ」という話の、対策側の実演
        webflux-blocking-isolated)
            VARIANT=webflux-jdk25; COND_ENV=()
            DEFAULT_MODES="blocking-isolated"; CROSSTALK_LOAD=blocking-isolated
            LAG_METRIC=EventLoopLagMillis ;;
        *)
            echo "未知の条件: $1" >&2; return 1 ;;
    esac
}

# pinned を JDK 25 で測ると「ピニングが起きない」結果になる（JEP 491）。それ自体は
# 見せたい結果だが、ピニングの計測だと思って読むと逆の結論になる。
# 黙って測ると区別できないので、実行中に必ず知らせる
warn_if_pinned_control() {
    local variant="$1" mode="$2"
    case "$variant" in
        *jdk25) ;;
        *) return 0 ;;
    esac
    case "$mode" in
        pinned|pinned-lock)
            echo "!!! JDK 25 の ${mode} は「JEP 491 で症状が消えること」の対照群です。"
            echo "!!! ピニング自体を測るには CONDITIONS=mvc-virtual-jdk21 を使ってください。"
            echo "!!! pinned-lock の効果を見るなら CONDITIONS=mvc-virtual-jdk21-lock です"
            echo "!!! （JDK 25 では pinned 側も詰まらないので、差が出ません）。" ;;
    esac
}

# SERVER_PREP_STMTS が効くのは JDBC 経路だけ。webflux-r2dbc の db / bounded / nodb は
# r2dbc 経路を通るので、true にしても何も変わらない。
# 黙って無効になると「Reactor だからピニングが起きない」と読み違えるため必ず知らせる
warn_if_prep_has_no_effect() {
    if [ "$SERVER_PREP_STMTS" != true ]; then
        return 0
    fi
    if [ "$1" = webflux-r2dbc ]; then
        echo "!!! webflux-r2dbc は r2dbc 経路なので SERVER_PREP_STMTS は効きません。"
        echo "!!! ピニングを測るのは mvc 系の条件と webflux-blocking です。"
        echo "!!! /api/diagnostics の r2dbcPool.serverPrepStmts が false であることで確認できます。"
    fi
}

# ---------------------------------------------------------------- タスクの操作

APP_TASK=""
LOAD_TASK=""
APP_FAMILY=""
APP_IP=""
APP_START_MS=0
IDLE_RSS_MB=0

stop_task() {
    local arn="$1"
    if [ -n "$arn" ]; then
        aws_cli ecs stop-task --cluster "$CLUSTER" --task "$arn" \
            --reason "measure-aws.sh の後始末" >/dev/null 2>&1 || true
    fi
}
# 最後まで到達したかどうか。終了コードだけでは足りないため別に持つ。
# bash 3.2（macOS の既定）は set -u の中断を終了コード 0 で返す。
# 「$変数 の直後が全角文字」で変数名を取り違えて落ちる事故が実際にあり、
# そのとき呼び出し側には成功として見えていた
COMPLETED=false

cleanup() {
    local rc=$?
    stop_task "$LOAD_TASK"
    stop_task "$APP_TASK"
    LOAD_TASK=""
    APP_TASK=""
    if [ "$COMPLETED" != true ] && [ "$rc" = 0 ]; then
        echo "!!! 最後まで到達せずに終わりました。上のエラーを読んでください" >&2
        rc=1
    fi
    # 途中で落ちた計測を成功に見せないため、保存した終了コードを返す
    exit $rc
}
trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM

# KEY=VALUE の並びを containerOverrides の JSON にする
overrides_json() {
    local container="$1"
    shift
    printf '%s\n' "$@" | jq -R -s --arg name "$container" '
        {containerOverrides: [{
            name: $name,
            environment: (split("\n") | map(select(length > 0))
                | map(split("=") | {name: .[0], value: (.[1:] | join("="))}))
        }]}'
}

run_task() {
    local family="$1" security_group="$2" overrides="$3"
    aws_cli ecs run-task \
        --cluster "$CLUSTER" \
        --task-definition "$family" \
        --launch-type FARGATE \
        --count 1 \
        --network-configuration \
        "awsvpcConfiguration={subnets=[$SUBNETS],securityGroups=[$security_group],assignPublicIp=$ASSIGN_PUBLIC_IP}" \
        --overrides "$overrides" \
        --query 'tasks[0].taskArn' --output text
}

task_id() {
    printf '%s' "${1##*/}"
}

log_stream() {
    printf '%s/%s/%s' "$1" "$2" "$(task_id "$3")"
}

fetch_log() {
    aws_cli logs get-log-events --log-group-name "$1" --log-stream-name "$2" \
        --start-from-head --output json 2>/dev/null | jq -r '.events[].message' || true
}

dump_app_log() {
    echo "---- アプリのログ（末尾 40 行） ----"
    fetch_log "$APP_LOG_GROUP" "$(log_stream "$APP_FAMILY" app "$1")" | tail -40
}

# lastStatus が RUNNING になった時点ではまだ受け付けられない。
# タスク定義のヘルスチェックが HEALTHY になるまで待つ
wait_for_healthy() {
    local arn="$1" waited=0 status health
    while [ "$waited" -lt "$TASK_START_TIMEOUT_SEC" ]; do
        read -r status health <<<"$(aws_cli ecs describe-tasks --cluster "$CLUSTER" --tasks "$arn" \
            --query 'tasks[0].[lastStatus,healthStatus]' --output text)"
        case "$status:$health" in
            RUNNING:HEALTHY)
                return 0 ;;
            STOPPED:*)
                echo "アプリのタスクが起動前に停止しました:" >&2
                aws_cli ecs describe-tasks --cluster "$CLUSTER" --tasks "$arn" \
                    --query 'tasks[0].[stoppedReason,containers[0].reason]' --output text >&2
                dump_app_log "$arn" >&2
                return 1 ;;
        esac
        sleep 5
        waited=$((waited + 5))
    done
    echo "アプリのタスクが ${TASK_START_TIMEOUT_SEC}s で HEALTHY になりませんでした" >&2
    dump_app_log "$arn" >&2
    return 1
}

wait_for_stopped() {
    local arn="$1" waited=0 status
    while [ "$waited" -lt "$LOAD_TIMEOUT_SEC" ]; do
        status=$(aws_cli ecs describe-tasks --cluster "$CLUSTER" --tasks "$arn" \
            --query 'tasks[0].lastStatus' --output text)
        if [ "$status" = STOPPED ]; then
            return 0
        fi
        sleep 10
        waited=$((waited + 10))
    done
    echo "負荷タスクが ${LOAD_TIMEOUT_SEC}s で終わりませんでした" >&2
    return 1
}

private_ip() {
    aws_cli ecs describe-tasks --cluster "$CLUSTER" --tasks "$1" \
        --query "tasks[0].attachments[0].details[?name=='privateIPv4Address'].value | [0]" --output text
}

# RSS はアプリのコンテナが 1 秒ごとに標準出力へ吐いている。区間を指定して最大値を取る
peak_rss_mb() {
    local arn="$1" start_ms="$2" kb
    kb=$(aws_cli logs filter-log-events \
        --log-group-name "$APP_LOG_GROUP" \
        --log-stream-names "$(log_stream "$APP_FAMILY" app "$arn")" \
        --start-time "$start_ms" \
        --filter-pattern 'RSS_KB' \
        --output json 2>/dev/null \
        | jq -r '.events[].message' \
        | awk '{ if ($2 > max) max = $2 } END { print max + 0 }')
    awk -v kb="${kb:-0}" 'BEGIN { printf "%.0f", kb / 1024 }'
}

# scenario.js が出した BACKEND_ID 行から、段ごとに何本の Aurora 側接続を見たかを数える。
# k6 の VU は独立した JS ランタイムで集合を共有できないため、VU をまたいだ数え上げは
# k6 の中ではできない。ここでログをまとめて数えるのがその代わり。
# RDS Proxy 経由と直結でこの本数を比べるのが、多重化を見る一番直接的な読み方
backend_id_table() {
    local log="$1"
    echo '| 同時数 | 見た接続 ID 数 |'
    echo '|---|---|'
    # grep は 1 行も一致しないと 1 を返す。pipefail のもとでは失敗扱いになるので囲って潰す
    { grep -oE 'BACKEND_ID level=[0-9]+ id=[^[:space:]"]+' "$log" || true; } \
        | sort -u \
        | sed -E 's/^BACKEND_ID level=([0-9]+) .*/\1/' \
        | sort -n | uniq -c \
        | awk '{ printf "| %s | %s |\n", $2, $1 }'
}

# 計測区間で RDS Proxy がセッションをピニングしていた本数のピーク。
#
# backend_id_table が数えるのは「アプリが見た接続 ID」で、多重化が消えたことの間接的な観測に
# すぎない。プールサイズと同じ本数に張り付いた理由がピニングなのか別の何かなのかは区別できない。
# ピニングそのものはプロキシ側が数えているので、そちらを直接読んで裏を取る。
#
# ディメンションの組み合わせを決め打ちにしない。CloudWatch は完全一致でしか引けず、
# 組み合わせを外すと「0 本」という嘘の答えが黙って返る。実在の組み合わせを list-metrics で
# 引いてから問い合わせ、1 つも見つからなければ 0 ではなく n/a を返す
pinned_sessions_max() {
    local start_sec="$1" end_sec dimension_sets
    end_sec=$(date +%s)

    dimension_sets=$(aws_cli cloudwatch list-metrics \
        --namespace AWS/RDS \
        --metric-name DatabaseConnectionsCurrentlySessionPinned \
        --dimensions "Name=ProxyName,Value=$PROXY_NAME" \
        --query 'Metrics[].Dimensions' --output json 2>/dev/null) || dimension_sets='[]'

    # list-metrics が返すのは直近 2 週間にデータのあったメトリクスだけ。プロキシを作り直した
    # 直後や、この計測が最初の 1 回目だと空になりうる
    if [ "$(printf '%s' "$dimension_sets" | jq 'length')" = 0 ]; then
        printf 'n/a'
        return 0
    fi

    printf '%s' "$dimension_sets" | jq -c '.[]' | while read -r dimensions; do
        aws_cli cloudwatch get-metric-statistics \
            --namespace AWS/RDS \
            --metric-name DatabaseConnectionsCurrentlySessionPinned \
            --dimensions "$dimensions" \
            --start-time "$start_sec" --end-time "$end_sec" \
            --period 60 --statistics Maximum \
            --query 'Datapoints[].Maximum' --output text 2>/dev/null || true
    done | tr '\t' '\n' | awk 'BEGIN { max = 0 } $1 > max { max = $1 } END { printf "%d", max }'
}

# アプリが自分で申告したメトリクスを EMF の行から集計する。仮説 2 の観測側の答え合わせ。
#
# CloudWatch のメトリクスとしてではなく、EMF の行をログから直接読んでいる。EMF の抽出には
# 取り込みの遅れがあり、負荷が 70 秒しかないこの計測では最後の山を取り逃しうるため。
# CloudWatch 側に出しているのは CpuUtilized と同じ画に重ねるためのもので、
# 「その回のピークはいくらだったか」はログのほうが確実に揃う。
#
# 集計の仕方がメトリクスによって違う。スレッドの空き待ち時間は瞬間値なので最大（max）、ピニングの件数は
# 送信間隔ごとの区間値なので合計（add）。ここを取り違えると、件数が「一番多かった 5 秒間」に
# 縮んで見える。
#
# 見つからないときに 0 を返さないのは、プロキシのピニング本数と同じ理由。0 は「起きなかった」と
# 読めてしまい、実際には「観測が動いていなかった」のかもしれない。両者は区別しなければならない
emf_metric() {
    local metric="$1" arn="$2" start_ms="$3" aggregate="${4:-max}" value
    value=$(aws_cli logs filter-log-events \
        --log-group-name "$APP_LOG_GROUP" \
        --log-stream-names "$(log_stream "$APP_FAMILY" app "$arn")" \
        --start-time "$start_ms" \
        --filter-pattern "$metric" \
        --output json 2>/dev/null \
        | jq -r --arg metric "$metric" --arg aggregate "$aggregate" \
            '[.events[].message | fromjson? | .[$metric]] | map(select(type == "number"))
             | if length == 0 then empty
               elif $aggregate == "add" then add
               else max end')
    if [ -z "$value" ]; then
        printf 'n/a'
    else
        printf '%s' "$value"
    fi
}

# アプリが出した DB_THREADS 行を「使用中の接続の本数」ごとにまとめる。
#
# 使用中の本数（borrowed）と、DB の中で SLEEP を実行している本数（sleeping）は
# 同じ瞬間に取ってある。borrowed ごとに sleeping を並べると、差が DB の中の順番待ちになる。
# 段（同時数）ごとではなく borrowed ごとに集めるのは、段の切れ目の時刻をこちらが知らないため。
# 知りたいのは「何本使用中のときに、何本走っていたか」なので、この軸で足りる。
#
# 行が 1 つも無ければ、表ではなく n/a を返す（観測が動いていなかった。0 本ではない）
db_threads_table() {
    local arn="$1" start_ms="$2" rows
    rows=$(aws_cli logs filter-log-events \
        --log-group-name "$APP_LOG_GROUP" \
        --log-stream-names "$(log_stream "$APP_FAMILY" app "$arn")" \
        --start-time "$start_ms" \
        --filter-pattern 'DB_THREADS' \
        --output json 2>/dev/null \
        | jq -r '.events[].message' \
        | { grep -oE 'borrowed=[0-9]+ sleeping=[0-9]+ active=[0-9]+ running=-?[0-9]+' || true; })
    if [ -z "$rows" ]; then
        echo 'n/a'
        return 0
    fi
    echo '| 使用中の接続 | 標本数 | SLEEP 実行中（平均） | SLEEP 実行中（最大） | Threads_running（平均） |'
    echo '|---|---|---|---|---|'
    printf '%s\n' "$rows" \
        | sed -E 's/[a-z_]+=//g' \
        | awk '{ n[$1]++; s[$1] += $2; if ($2 > m[$1]) m[$1] = $2; r[$1] += $4 }
               END { for (b in n) printf "%d %d %.1f %d %.1f\n", b, n[b], s[b] / n[b], m[b], r[b] / n[b] }' \
        | sort -n \
        | awk '{ printf "| %s | %s | %s | %s | %s |\n", $1, $2, $3, $4, $5 }'
}

# ---------------------------------------------------------------- 実行の単位

start_app() {
    local family="$1"
    shift
    APP_FAMILY="$family"
    APP_START_MS=$(( $(date +%s) * 1000 ))
    APP_TASK=$(run_task "$family" "$APP_SG" "$(overrides_json app "$@")")
    echo "    タスク: $(task_id "$APP_TASK")"
    wait_for_healthy "$APP_TASK"
    APP_IP=$(private_ip "$APP_TASK")
    # 起動直後の RSS。負荷をかける前の区間の最大値を取る
    IDLE_RSS_MB=$(peak_rss_mb "$APP_TASK" "$APP_START_MS")
    echo "    アプリ: http://$APP_IP:8080 (起動直後 ${IDLE_RSS_MB} MB)"
}

# k6（または引数で渡した任意のコマンド）を VPC 内から 1 回流し、ログを出力先へ書く。
# サブシェルで呼ぶと LOAD_TASK が親に伝わらず、中断時にタスクを止められないので、
# ログはコマンド置換ではなくファイルに書く
run_load() {
    local overrides="$1" destination="$2"
    LOAD_TASK=$(run_task "$LOAD_TASK_FAMILY" "$LOAD_SG" "$overrides")
    wait_for_stopped "$LOAD_TASK"
    fetch_log "$LOAD_LOG_GROUP" "$(log_stream k6 k6 "$LOAD_TASK")" > "$destination"
    LOAD_TASK=""
}

# ---------------------------------------------------------------- verify

# 構成が意図どおりかを、計測を始める前に確認する。
# 手元からアプリへ届く経路はわざと作っていない（8080 は k6 のタスクからのみ）ので、
# 疎通確認も VPC 内のタスクから行うしかない
verify() {
    echo "==> スタック出力"
    printf '%s' "$outputs_json" | jq -r '.[] | "  \(.OutputKey) = \(.OutputValue)"'

    echo
    echo "==> RDS Proxy"
    aws_cli rds describe-db-proxies --db-proxy-name "$PROXY_NAME" \
        --query 'DBProxies[0].[DBProxyName,Status,EngineFamily,RequireTLS,IdleClientTimeout]' --output text
    aws_cli rds describe-db-proxy-targets --db-proxy-name "$PROXY_NAME" \
        --query 'Targets[].[Type,RdsResourceId,Port,TargetHealth.State]' --output text

    echo
    echo "==> Aurora"
    aws_cli rds describe-db-clusters \
        --query "DBClusters[?starts_with(DBClusterIdentifier, 'cnj-concurrency-model')].[DBClusterIdentifier,Status,EngineVersion,ServerlessV2ScalingConfiguration.MinCapacity,ServerlessV2ScalingConfiguration.MaxCapacity]" \
        --output text

    echo
    echo "==> タスク定義"
    stack_output AppTaskFamilies | tr ',' '\n' | sed 's/^/  /'

    # RDS Proxy 経由と直結の両方を確かめる。接続先はアプリの起動時に決まるので、
    # 経路ごとにアプリを立て直す
    local target host log
    log=$(mktemp)
    for target in proxy direct; do
        if [ "$target" = proxy ]; then host="$PROXY_ENDPOINT"; else host="$WRITER_ENDPOINT"; fi
        echo
        # 変数名の直後が全角文字のときは ${} で囲む。bash はロケール次第で
        # 全角文字の先頭バイトを変数名の一部として読み、$host） が host? という
        # 存在しない変数の参照になって set -u で落ちる
        echo "==> 疎通確認（${target}: ${host}）"
        # 条件名を verify にしておく。疎通確認で出た EMF が計測本番の次元に混ざると、
        # 「負荷をかけていない区間の 0 ms」が同じ系列に入って山を薄める
        start_app "cnj-cm-mvc-jdk25-cpu1024" \
            "DB_HOST=$host" "POOL_SIZE=10" "WORKLOAD_PERMITS=10" "VIRTUAL_THREAD_ENABLED=true" \
            "SERVER_PREP_STMTS=$SERVER_PREP_STMTS" "METRICS_CONDITION=verify"

        # 先に /api/diagnostics を 1 回。接続先と serverPrepStmts の実効値がここに出るので、
        # sweep を丸ごと流す前に「スイッチがアプリまで届いているか」をここで確かめられる。
        #
        # 続けて /api/db を 10 回。backendConnectionId が散らばれば接続が多重化されている。
        # 直結なら Hikari の接続 1 本に固定されるので、ID もほぼ固定になる
        run_load "$(jq -nc --arg url "http://$APP_IP:8080" '
            {containerOverrides: [{
                name: "k6",
                environment: [{name: "BASE_URL", value: $url}],
                command: ["sh", "-c",
                    "wget -qO- \"$BASE_URL/api/diagnostics\"; echo; i=0; while [ $i -lt 10 ]; do wget -qO- \"$BASE_URL/api/db?ms=200\"; echo; i=$((i+1)); done"]
            }]}')" "$log"
        cat "$log"

        stop_task "$APP_TASK"
        APP_TASK=""
    done
    rm -f "$log"

    echo
    echo "==> 確認おわり。タスクは停止済み。"
    echo "    Aurora と RDS Proxy は動いたままなので、しばらく使わないなら cdk destroy すること。"
}

# ---------------------------------------------------------------- sweep

# 列を増やしたので、古い見出しのまま行を足すと表がずれる。新しい列が見出しに無ければ
# 同じファイルに表を出し直す（過去の結果は消さずに残す）
if [ ! -f "$SUMMARY" ] || ! grep -q 'VT ピニング' "$SUMMARY"; then
    {
        echo '### AWS 上の計測（RSS はアプリのコンテナが /proc から 1 秒ごとに採取）'
        echo
        # ピニングが 2 種類あるので列名で分ける。Proxy 側は RDS Proxy が接続を多重化できなくなった
        # セッション数（仮説 1 の側）、VT 側は仮想スレッドがキャリアを離せなかった回数（仮説 2 の側）
        echo '| 条件 | サイズ | プール | モード | DB 経路 | prep | Proxy ピニング | VT ピニング | 最大 lag ms | 起動直後 MB | ピーク MB | 結果 |'
        echo '|---|---|---|---|---|---|---|---|---|---|---|---|'
    } >> "$SUMMARY"
fi

sweep() {
    local condition size pool mode modes label start_ms prep_suffix pinned lag
    local vt_pinned vt_pinned_max vt_pinned_cell
    # ピニングを測った結果が、測っていない結果を同じ名前で上書きするのを防ぐ。
    # false のときに接尾辞を付けないのは、既存の結果ファイル名と揃えておくため
    prep_suffix=""
    if [ "$SERVER_PREP_STMTS" = true ]; then
        prep_suffix="-prepon"
    fi
    for condition in $CONDITIONS; do
        resolve_condition "$condition"
        for size in $SIZES; do
            for pool in $POOL_SIZES; do
                modes="${MODES:-$DEFAULT_MODES}"
                if [ "$SCRIPT" = crosstalk ]; then
                    modes="$CROSSTALK_LOAD"
                fi

                echo "==> $condition / $size / pool=$pool / DB=$DB_TARGET / prep=$SERVER_PREP_STMTS"
                # METRICS_CONDITION は EMF の次元になる。ここで条件名をそのまま渡すことで、
                # アプリが自分で出した遅延を CloudWatch 上で CpuUtilized と同じ軸に並べられる。
                # 「CPU は暇なのに詰まっている」を 1 枚の画で見せるための配線
                start_app "cnj-cm-${VARIANT}-${size}" \
                    ${COND_ENV[@]+"${COND_ENV[@]}"} \
                    "DB_HOST=$DB_HOST" "POOL_SIZE=$pool" "WORKLOAD_PERMITS=$pool" \
                    "SERVER_PREP_STMTS=$SERVER_PREP_STMTS" \
                    "METRICS_CONDITION=$condition" \
                    "DB_OBSERVER_HOST=$WRITER_ENDPOINT"
                warn_if_prep_has_no_effect "$condition"

                for mode in $modes; do
                    warn_if_pinned_control "$VARIANT" "$mode"
                    label="aws-${condition}-${size}-pool${pool}-hold${HOLD_MS}-${SCRIPT}-${mode}${prep_suffix}-${DB_TARGET}-${RUN_ID}"
                    echo "    $SCRIPT: $mode"
                    start_ms=$(( $(date +%s) * 1000 ))

                    if [ "$SCRIPT" = crosstalk ]; then
                        run_load "$(overrides_json k6 \
                            "BASE_URL=http://$APP_IP:8080" \
                            "K6_SCRIPT=/scripts/k6/crosstalk.js" \
                            "LOAD_MODE=$mode" \
                            "LOAD_VUS=${LOAD_VUS:-30}" \
                            "LOAD_HOLD_MS=${LOAD_HOLD_MS:-1000}" \
                            "PROBE_HOLD_MS=${PROBE_HOLD_MS:-10}" \
                            "LABEL=$label" "RESULTS_DIR=/tmp")" \
                            "$RESULTS_DIR/${label}.log"
                    else
                        run_load "$(overrides_json k6 \
                            "BASE_URL=http://$APP_IP:8080" \
                            "K6_SCRIPT=/scripts/k6/scenario.js" \
                            "MODE=$mode" "HOLD_MS=$HOLD_MS" \
                            "LEVELS=${LEVELS:-}" \
                            "LEVEL_HOLD_SECONDS=${LEVEL_HOLD_SECONDS:-30}" \
                            "LABEL=$label" "RESULTS_DIR=/tmp")" \
                            "$RESULTS_DIR/${label}.log"
                    fi

                    # ピニングの本数はプロキシ側にしか無い数字なので、直結では聞かない。
                    # 直結で 0 と書くと「ピニングされなかった」と読めてしまう
                    if [ "$DB_TARGET" = proxy ]; then
                        pinned=$(pinned_sessions_max "$(( start_ms / 1000 ))")
                    else
                        pinned="-"
                    fi

                    lag=$(emf_metric "$LAG_METRIC" "$APP_TASK" "$start_ms")

                    # 空き待ち時間が延びた原因側。件数は区間値なので合計、最長は最大で取る。
                    # n/a は「JFR を購読していない」（webflux 条件、または購読に失敗）で、
                    # 0 件は「ピニングが起きなかった」。JDK 25 の条件で 0 件になるのは正しい結果
                    vt_pinned=$(emf_metric PinnedEvents "$APP_TASK" "$start_ms" add)
                    if [ "$vt_pinned" = n/a ]; then
                        vt_pinned_cell="n/a"
                    else
                        vt_pinned_max=$(emf_metric PinnedMaxMillis "$APP_TASK" "$start_ms")
                        vt_pinned_cell="${vt_pinned} 件 / 最長 ${vt_pinned_max} ms"
                    fi

                    printf '| %s | %s | %s | %s | %s | %s | %s | %s | %s | %s | %s | %s |\n' \
                        "$condition" "$size" "$pool" "$mode" "$DB_TARGET" \
                        "$SERVER_PREP_STMTS" "$pinned" "$vt_pinned_cell" "$lag" \
                        "$IDLE_RSS_MB" "$(peak_rss_mb "$APP_TASK" "$start_ms")" "${label}.log" \
                        >> "$SUMMARY"
                    tail -30 "$RESULTS_DIR/${label}.log"
                    echo
                    # この 1 行が仮説 2 の核。CpuUtilized を別で引いて並べたときに、
                    # 「CPU が低いほうが lag が大きい」という逆転が読めるかどうかを見る
                    echo "    ${LAG_METRIC} のピーク: ${lag} ms"
                    # 原因と結果を並べて出す。lag が伸びているのにこちらが 0 件なら、
                    # 詰まりの原因はピニングではない（プールの待ちなど別のもの）
                    echo "    jdk.VirtualThreadPinned: ${vt_pinned_cell}"

                    # 接続 ID の数え上げはログを読み終えたあとに別ファイルへ出す。
                    # 同じファイルへ追記すると、数えている途中の出力を数え直すことになる
                    if [ "$SCRIPT" != crosstalk ]; then
                        backend_id_table "$RESULTS_DIR/${label}.log" \
                            > "$RESULTS_DIR/${label}.backend-ids.md"
                        echo
                        # 変数名の直後が全角文字のときは ${} で囲む（verify で踏んだのと同じ罠）
                        echo "    VU をまたいで見た接続 ID（DB 経路 = ${DB_TARGET}、prep = ${SERVER_PREP_STMTS}）:"
                        sed 's/^/    /' "$RESULTS_DIR/${label}.backend-ids.md"
                    fi

                    # DB の中で SLEEP が何本走っていたか。webflux 条件は観測を積んでいないので n/a になる
                    if [ "$SCRIPT" != crosstalk ]; then
                        db_threads_table "$APP_TASK" "$start_ms" > "$RESULTS_DIR/${label}.db-threads.md"
                        echo
                        echo "    使用中の接続と、DB の中で SLEEP を実行している本数（観測は writer 直結）:"
                        sed 's/^/    /' "$RESULTS_DIR/${label}.db-threads.md"
                    fi

                    # ピニングの本数は 1 分粒度で、プロキシ側の反映に数十秒の遅れがある。
                    # 段が短いと最後の点を取り逃すため、0 本は「起きなかった」の確証にしない
                    if [ "$DB_TARGET" = proxy ]; then
                        echo "    ピニングされたセッション数のピーク: ${pinned}"
                    fi
                done

                stop_task "$APP_TASK"
                APP_TASK=""
            done
        done
    done
    echo "==> 完了。結果は $RESULTS_DIR/aws-*.log、一覧は $SUMMARY"
}

case "${1:-sweep}" in
    verify) verify ;;
    sweep) sweep ;;
    *) echo "使い方: $0 [verify|sweep]" >&2; exit 1 ;;
esac
COMPLETED=true
