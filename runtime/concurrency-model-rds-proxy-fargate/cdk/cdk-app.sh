#!/usr/bin/env bash
# cdk.json の app から呼ばれる。CDK アプリを JDK 21 で合成する。
#
# Gradle 9.7.1 自体は JDK 25 でも動くので「動かすための JDK」の制約はもうないが、
# toolchain が 21 なのでコンパイルには JDK 21 が要る。Gradle の toolchain 自動検出に
# 任せると「見つからなければダウンロードを試みて失敗する」という遠い場所のエラーになるため、
# ここで JDK 21 を解決して Gradle 自体もそれで動かす（自動検出に頼らず確実に通す）。
# cdk synth / cdk deploy を叩くたびに JAVA_HOME を手で指定させたくないので、ここで解決する。
set -euo pipefail

cd "$(dirname "$0")"

java_major() {
    "$1/bin/java" -XshowSettings:properties -version 2>&1 \
        | awk -F'= *' '/java.specification.version/ { print $2 }'
}

# 明示された JAVA_HOME が使えるならそれを尊重する。CI やほかの OS で
# /usr/libexec/java_home がないことを前提にできないため
if [ -n "${JAVA_HOME:-}" ] && [ "$(java_major "$JAVA_HOME")" -lt 24 ] 2>/dev/null; then
    : # そのまま使う
elif [ -x /usr/libexec/java_home ]; then
    JAVA_HOME=$(/usr/libexec/java_home -v 21 2>/dev/null || true)
    [ -n "$JAVA_HOME" ] || {
        echo "JDK 21 が見つかりません。CDK は toolchain 21 でコンパイルするため JDK 21 が必要です。" >&2
        exit 1
    }
else
    echo "JAVA_HOME が JDK 24 以降か未設定で、JDK 21 を自動解決できません。" >&2
    echo "JAVA_HOME に JDK 21 を指定してください。" >&2
    exit 1
fi
export JAVA_HOME

exec ../gradlew -p . run --quiet --console=plain
