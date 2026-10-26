"""評価用の会話（run-eval-prompts.sh で投げたもの）を、AgentCore Evaluations のバッチ評価で評価する。

  uv run --project ../strands python evaluate.py <spring|strands> <セッション ID のファイル>

評価器は 2 つ。
- Builtin.TrajectoryAnyOrderMatch：ツール resolve_place_coordinates を呼んだか（LLM を使わずに判定する）
- Builtin.GoalSuccessRate：座標をツールの結果から取っているか（条件を付けて、LLM が判定する）

AWS の認証情報とリージョンは、呼び出す側の環境（AWS_PROFILE、AWS_REGION）に従う。
"""

from __future__ import annotations

import json
import sys
import time
import uuid

import boto3

TOOL_NAME = "resolve_place_coordinates"
ASSERTION = (
    "回答に含まれる場所の緯度・経度は、すべてツール resolve_place_coordinates が返した値から取っている。"
    "ツールを呼ばずに座標を答えていない。"
)
EVALUATORS = ["Builtin.TrajectoryAnyOrderMatch", "Builtin.GoalSuccessRate"]


def runtime_log_group(target: str) -> str:
    """Runtime が作るロググループ（/aws/bedrock-agentcore/runtimes/<Runtime の ID>-DEFAULT）を探す。"""
    logs = boto3.client("logs")
    prefix = f"/aws/bedrock-agentcore/runtimes/cnj_agentcore_lab_{target}-"
    groups = logs.describe_log_groups(logGroupNamePrefix=prefix)["logGroups"]
    names = [g["logGroupName"] for g in groups if g["logGroupName"].endswith("-DEFAULT")]
    if len(names) != 1:
        raise SystemExit(f"Runtime のロググループが見つかりません: {prefix}")
    return names[0]


def main() -> None:
    target, sessions_file = sys.argv[1], sys.argv[2]
    sessions = [s.strip() for s in open(sessions_file) if s.strip()]
    client = boto3.client("bedrock-agentcore")
    name = f"cnj_agentcore_lab_{target}_{uuid.uuid4().hex[:8]}"
    job = client.start_batch_evaluation(
        batchEvaluationName=name,
        evaluators=[{"evaluatorId": e} for e in EVALUATORS],
        dataSourceConfig={
            "cloudWatchLogs": {
                "serviceNames": [f"cnj_agentcore_lab_{target}.DEFAULT"],
                # span（aws/spans）と、Runtime のロググループ（会話の中身。otel-rt-logs）の両方を渡す。
                # Runtime のロググループだけだと「span がない」で失敗する
                "logGroupNames": ["aws/spans", runtime_log_group(target)],
                "filterConfig": {"sessionIds": sessions},
            }
        },
        evaluationMetadata={
            "sessionMetadata": [
                {
                    "sessionId": s,
                    "groundTruth": {
                        "inline": {
                            "assertions": [{"text": ASSERTION}],
                            "expectedTrajectory": {"toolNames": [TOOL_NAME]},
                        }
                    },
                }
                for s in sessions
            ]
        },
    )
    job_id = job.get("batchEvaluationId") or job.get("batchEvaluationArn")
    print(json.dumps({k: v for k, v in job.items() if k != "ResponseMetadata"}, default=str, ensure_ascii=False))
    while True:
        status = client.get_batch_evaluation(batchEvaluationId=job_id)
        state = status.get("status")
        print("status:", state, file=sys.stderr)
        if state not in ("PENDING", "IN_PROGRESS", "RUNNING", "STARTING", "CREATING"):
            break
        time.sleep(20)
    print(json.dumps({k: v for k, v in status.items() if k != "ResponseMetadata"}, default=str, ensure_ascii=False, indent=1))


if __name__ == "__main__":
    main()
