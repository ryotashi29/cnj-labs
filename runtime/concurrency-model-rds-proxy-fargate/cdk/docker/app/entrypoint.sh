#!/usr/bin/env bash
# アプリを起動し、RSS を 1 秒ごとに標準出力へ吐く。
#
# RSS をプロセスの外から測るのは、/api/diagnostics が返すヒープと非ヒープには
# JVM 自身のオーバーヘッド・スレッドスタック・Netty のダイレクトバッファが入らず、
# 3 モデルの差はまさにそこに出るため。標準出力に出しておけば CloudWatch Logs から回収できる。
set -euo pipefail

# JAVA_OPTS には -Djdk.virtualThreadScheduler.parallelism や -Dreactor.netty.ioWorkerCount のような
# 条件ごとのフラグが入る。単語分割させたいので引用しない
# shellcheck disable=SC2086
java ${JAVA_OPTS:-} -jar /opt/app/app.jar &
APP_PID=$!

# ECS が止めるときの SIGTERM を JVM に渡す。ここを繋がないと猶予時間ぶん待たされ、
# 条件を切り替える回転が落ちる
trap 'kill -TERM "$APP_PID" 2>/dev/null || true' TERM INT

# ps を使わず /proc を bash の read で読む。0.25 vCPU のタスクでは毎秒の
# 「ps + パイプ」が計測対象の CPU を目に見えて削るため。read は組み込みなので
# 追加のプロセスを作らない（避けられないのは sleep の 1 回だけ）
if [ "${RSS_SAMPLE_INTERVAL_SEC:-1}" != "0" ]; then
    (
        while kill -0 "$APP_PID" 2>/dev/null; do
            while read -r key value _; do
                if [ "$key" = "VmRSS:" ]; then
                    echo "RSS_KB $value"
                fi
            done < "/proc/$APP_PID/status"
            sleep "${RSS_SAMPLE_INTERVAL_SEC:-1}"
        done
    ) &
fi

# アプリの終了コードをそのままコンテナの終了コードにする。
# 起動失敗を成功として扱うと、計測が空振りしたことに気づけない
wait "$APP_PID"
