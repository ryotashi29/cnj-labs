#!/bin/bash
# 利用者の ID トークン（JWT）を取って標準出力に出す。Runtime と Gateway の両方で、このトークンで認証する。
#   TOKEN=$(./scripts/id-token.sh <ユーザー名> <パスワード>)
set -euo pipefail
cd "$(dirname "$0")"
CLIENT_ID=$(./stack-output.sh UserPoolClientId)
aws cognito-idp initiate-auth --client-id "$CLIENT_ID" --auth-flow USER_PASSWORD_AUTH \
    --auth-parameters "USERNAME=${1:?ユーザー名},PASSWORD=${2:?パスワード}" \
    --query 'AuthenticationResult.IdToken' --output text
