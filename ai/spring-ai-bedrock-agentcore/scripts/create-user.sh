#!/bin/bash
# 検証用の利用者を Cognito に作る。パスワードは自分で決めて渡す（スクリプトには書かない）。
#   ./scripts/create-user.sh <ユーザー名> <パスワード>
# 2 人目の利用者を作れば、「別の利用者が同じ会話を読めないか」を確かめられる。
set -euo pipefail
cd "$(dirname "$0")"
USER_NAME="${1:?ユーザー名を指定してください}"
PASSWORD="${2:?パスワードを指定してください}"
POOL_ID=$(./stack-output.sh UserPoolId)
aws cognito-idp admin-create-user --user-pool-id "$POOL_ID" --username "$USER_NAME" \
    --message-action SUPPRESS > /dev/null
aws cognito-idp admin-set-user-password --user-pool-id "$POOL_ID" --username "$USER_NAME" \
    --password "$PASSWORD" --permanent
echo "作りました: $USER_NAME"
