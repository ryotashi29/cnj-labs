#!/bin/bash
# スタックの出力（Outputs）を 1 つ取り出す。ほかのスクリプトから使う。
#   ./scripts/stack-output.sh <出力の名前>
# AWS の認証情報とリージョンは、呼び出す側の環境（AWS_PROFILE、AWS_REGION など）に従う。
set -euo pipefail
aws cloudformation describe-stacks --stack-name CnjAgentCoreLab \
    --query "Stacks[0].Outputs[?OutputKey=='${1:?出力の名前を指定してください}'].OutputValue" --output text
