"""AgentCore Gateway の Lambda ターゲット：地名から座標を引く（Nominatim）。

Spring AI 版・Strands 版の両方のエージェントが、Gateway を通してこのツールを使う。
ツールの形（スキーマ）は CDK 側（``cdk`` の ``ToolDefinition``）で定め、ここはその中身。

**入出力**（CDK の ToolDefinition と揃えること）::

    入力: {"queries": ["代々木公園", "昭和記念公園"]}
    出力: {"代々木公園": [{"name": ..., "displayName": ..., "lat": ..., "lng": ...}], ...}

**LLM を呼ぶ回数を増やさない**：ツールは複数の地名を 1 回で受け、並列に問い合わせる。
1 件ずつ呼ばせると、LLM との往復が増えて遅くなる。

**Nominatim の利用規約**：識別できる User-Agent を付け、問い合わせの間隔を空ける
（``MIN_INTERVAL_SECONDS``）。CDK で Lambda の同時実行数を 1 にして、呼び出しをまたいでも間隔が効くようにしている。
"""

from __future__ import annotations

import concurrent.futures
import json
import os
import threading
import time
import urllib.error
import urllib.parse
import urllib.request

NOMINATIM_BASE = os.environ.get("NOMINATIM_BASE_URL", "https://nominatim.openstreetmap.org")

# Nominatim の規約上、連絡先を含む識別可能な User-Agent が必須。
USER_AGENT = os.environ.get(
    "NOMINATIM_USER_AGENT",
    "cnj-labs-spring-ai-bedrock-agentcore/0.1 (https://github.com/ryotashi29/cnj-labs)",
)

# 1クエリあたりの候補数。多すぎるとモデルへの入力トークンが増えて遅くなるだけなので絞る。
CANDIDATES_PER_QUERY = int(os.environ.get("CANDIDATES_PER_QUERY", "3"))

# 同時に投げる本数。Usage Policy に配慮して絞る（プロセス内実装は8だった）。
# 完全直列にするとクエリ数ぶんレイテンシが積み上がるので、間隔制御と併用して妥協点を取る。
MAX_WORKERS = int(os.environ.get("GEOCODING_MAX_WORKERS", "3"))

# リクエスト開始の最小間隔（秒）。Nominatim の目安は 1 req/s。
# Lambda の予約同時実行数を1にしてプロセス横断でもこの間隔が効くようにしている（GatewayStack）。
MIN_INTERVAL_SECONDS = float(os.environ.get("NOMINATIM_MIN_INTERVAL_SECONDS", "1.0"))

# Nominatim 1件あたりのタイムアウト（秒）。
REQUEST_TIMEOUT_SECONDS = float(os.environ.get("NOMINATIM_TIMEOUT_SECONDS", "10"))

# ツール名。Gateway は client_context 経由でツール名を渡してくるので、想定外の名前を弾くのに使う。
TOOL_NAME = "resolve_place_coordinates"

class _RateLimiter:
    """リクエスト開始の間隔を最低 ``min_interval`` 秒空ける。

    並列度を残したまま送出レートだけを抑えるための仕組み。``ThreadPoolExecutor`` の
    ワーカーがここで順番待ちするので、並列に投げつつ 1 req/s を超えない。

    Lambda の実行環境はコンテナ再利用で状態が残るため、直近の送出時刻をインスタンスに
    持たせておくと**呼び出しをまたいでも**間隔が効く（連続呼び出しで規約違反になりにくい）。
    """

    def __init__(self, min_interval: float) -> None:
        self._min_interval = min_interval
        self._lock = threading.Lock()
        self._next_allowed = 0.0

    def acquire(self) -> None:
        """次に送出してよい時刻まで待つ。"""
        with self._lock:
            now = time.monotonic()
            wait = self._next_allowed - now
            # 次の送出可能時刻を先に予約してからロックを解放するので、待っている間に
            # 他スレッドが同じスロットを取ることはない。
            self._next_allowed = max(now, self._next_allowed) + self._min_interval
        if wait > 0:
            time.sleep(wait)


# Lambda コンテナ再利用時に間隔制御を引き継ぐため、モジュールスコープで保持する。
_rate_limiter = _RateLimiter(MIN_INTERVAL_SECONDS)


def search_places(query: str, limit: int = CANDIDATES_PER_QUERY) -> list[dict]:
    """地名・住所・施設名から場所候補を検索する（フォワードジオコーディング）。

    日本（``countrycodes=jp``）に絞り、日本語表記で最大 ``limit`` 件返す。
    失敗・空クエリ時は空リストを返す（例外を投げない）。個別の失敗でツール全体を
    落とすと、他のクエリで取れた候補まで捨てることになるため。
    """
    q = (query or "").strip()
    if not q:
        return []

    params = urllib.parse.urlencode(
        {
            "format": "jsonv2",
            "q": q,
            "accept-language": "ja",
            "countrycodes": "jp",
            "limit": str(limit),
        }
    )
    request = urllib.request.Request(  # noqa: S310 - スキームは定数の NOMINATIM_BASE 由来
        f"{NOMINATIM_BASE}/search?{params}",
        headers={"User-Agent": USER_AGENT, "Accept": "application/json"},
    )

    _rate_limiter.acquire()
    try:
        with urllib.request.urlopen(  # noqa: S310 - 同上
            request, timeout=REQUEST_TIMEOUT_SECONDS
        ) as response:
            data = json.loads(response.read().decode("utf-8"))
    except (urllib.error.URLError, TimeoutError, ValueError, OSError):
        return []

    if not isinstance(data, list):
        return []

    candidates: list[dict] = []
    for item in data:
        if not isinstance(item, dict):
            continue
        display_name = item.get("display_name") or ""
        # name（施設名等）が空なら display_name の先頭要素をスポット名の候補にする。
        name = item.get("name") or (display_name.split(",")[0].strip() if display_name else "")
        try:
            lat = float(item["lat"])
            lng = float(item["lon"])
        except (KeyError, TypeError, ValueError):
            continue
        candidates.append(
            {
                "name": name,
                "displayName": display_name,
                "lat": lat,
                "lng": lng,
            }
        )
    return candidates


def resolve_place_coordinates(queries: list[str]) -> dict[str, list[dict]]:
    """複数クエリの座標をまとめて解決する。

    Nominatim は1件あたり1秒前後かかるため並列に投げる（送出間隔は ``_RateLimiter`` が担保）。
    """
    targets = [q for q in queries if isinstance(q, str) and q.strip()]
    if not targets:
        return {}

    with concurrent.futures.ThreadPoolExecutor(
        max_workers=min(MAX_WORKERS, len(targets))
    ) as executor:
        results = list(executor.map(search_places, targets))
    return dict(zip(targets, results, strict=False))


def _tool_name(context: object) -> str | None:
    """Gateway が渡してくるツール名を取り出す。

    Lambda ターゲットでは呼ばれたツール名が ``context.client_context.custom`` に入る。
    ツールを増やしたときにここで分岐できるようにしておく（今は1つだけ）。
    キー名は実装依存なので、取れなかった場合は名前チェックをスキップする（呼び出しを
    落とさない）。
    """
    client_context = getattr(context, "client_context", None)
    custom = getattr(client_context, "custom", None)
    if not isinstance(custom, dict):
        return None
    for key in ("bedrockAgentCoreToolName", "bedrockagentcoreToolName", "toolName"):
        value = custom.get(key)
        if isinstance(value, str) and value:
            # Gateway は "<target名>___<ツール名>" の形で渡すことがあるため末尾を見る。
            return value.split("___")[-1]
    return None


def handler(event: dict, context: object) -> dict:
    """Gateway の Lambda ターゲットのエントリポイント。

    Args:
        event: ツールの入力。``{"queries": [...]}``。
        context: Lambda コンテキスト。``client_context.custom`` にツール名が入る。

    Returns:
        クエリをキー、候補リストを値とする dict。入力が不正なら空 dict。
    """
    name = _tool_name(context)
    if name is not None and name != TOOL_NAME:
        # 想定外のツール名。スキーマ（CDK）と実装がずれた状態なので黙って空を返さず明示する。
        return {"error": f"未対応のツールです: {name}"}

    queries = (event or {}).get("queries")
    if not isinstance(queries, list):
        return {}
    return resolve_place_coordinates(queries)
