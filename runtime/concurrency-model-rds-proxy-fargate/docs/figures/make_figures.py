"""記事用の図を 2 枚描く。

入力は k6/results/raw-logs/ に書き出した生データ（CloudWatch Logs は 7 日で消えるため手元に退避したもの）。
git には入れていないので、この図を描き直せるのはデータを持っている手元だけ。

  claim2-cpu-inversion.png  仮説 2: 問題のある実装だけ CPU 使用率が低く、待ち時間が跳ね上がる（計測 2-2）
  claim1-session-pinning.png  仮説 1: useServerPrepStmts を有効にすると、アプリの接続がすべてピニングされる
  claim1-db-threads.png     仮説 1: 接続は足りているのに DB の中で順番待ちしている（#18 の観測）

実行: python docs/figures/make_figures.py
"""

import json
from datetime import datetime, timedelta, timezone
from pathlib import Path

import matplotlib

matplotlib.use("Agg")
import matplotlib.pyplot as plt  # noqa: E402

HERE = Path(__file__).resolve().parent
RAW = HERE.parent.parent / "k6" / "results" / "raw-logs"

# 参照パレット（dataviz）の先頭 3 色。条件の色は全パネルで固定し、順位で塗り替えない
BROKEN = "#eb6834"
FIX_CODE = "#2a78d6"
FIX_JDK = "#1baf7a"
INK = "#0b0b0b"
INK_2 = "#52514e"
GRID = "#e4e3df"
SURFACE = "#fcfcfb"

plt.rcParams.update({
    "font.family": ["Hiragino Sans", "YuGothic", "sans-serif"],
    "font.size": 11,
    "axes.edgecolor": GRID,
    "axes.labelcolor": INK_2,
    "xtick.color": INK_2,
    "ytick.color": INK,
    "figure.facecolor": SURFACE,
    "axes.facecolor": SURFACE,
    "savefig.facecolor": SURFACE,
})

JST = timezone(timedelta(hours=9))


# ---------------------------------------------------------------- 仮説 2

def cpu_range(family, load_start, load_end):
    """負荷区間にかかる 1 分の点の CpuUtilized（最大）を、予約 256 ユニットに対する % で返す。

    Container Insights は 1 分粒度しかない。負荷は 70 秒なので 2 点にまたがり、どちらも
    負荷の前後を少し含む。1 点を選ぶと恣意的になるので、範囲（最小〜最大）として出す。
    """
    values = []
    for line in (RAW / f"cpu-1b-{family}.jsonl").read_text().splitlines():
        point = json.loads(line)
        minute = datetime.fromisoformat(point["Timestamp"])
        if minute + timedelta(minutes=1) > load_start and minute < load_end:
            values.append(point["Maximum"] / 256 * 100)
    return min(values), max(values)


def load_window(end_jst):
    """k6 の crosstalk は 115 秒。負荷は開始 20 秒後から 70 秒間。ログの書き終わりから逆算する。"""
    end = datetime.fromisoformat(end_jst).replace(tzinfo=JST)
    start = end - timedelta(seconds=115)
    return start + timedelta(seconds=20), start + timedelta(seconds=90)


def claim2():
    # 処理件数は負荷中の毎秒の件数（k6 のログを 1 秒ごとに見た値。README 計測 2-2）。
    # 成功数 ÷ 70 秒にしないのは、成功数に負荷を止めた後の猶予（gracefulStop）に返った分が入るため。
    # 問題ありは前半が毎秒 1 件、後半が毎秒 2 件。スレッドの空き待ち時間の最大は EMF のピーク
    conditions = [
        # 表示名, 色, タスク定義, ログの書き終わり（JST）, 毎秒の処理件数（最小, 最大）, スレッドの空き待ち時間の最大 ms
        ("問題あり\nJDK 21 + synchronized", BROKEN, "cnj-cm-mvc-jdk21-cpu256", "2026-09-24T23:11:27", (1, 2), 29750),
        ("対策① コード修正\nJDK 21 + ReentrantLock", FIX_CODE, "cnj-cm-mvc-jdk21-cpu256", "2026-09-24T23:07:14", (30, 30), 0),
        ("対策② JDK 更新\nJDK 25 + synchronized", FIX_JDK, "cnj-cm-mvc-jdk25-cpu256", "2026-09-24T23:15:56", (30, 30), 0),
    ]
    rows = []
    for name, color, family, end, rps, lag_ms in conditions:
        lo, hi = cpu_range(family, *load_window(end))
        rows.append((name, color, rps, lo, hi, lag_ms / 1000))

    fig, axes = plt.subplots(1, 3, figsize=(12.5, 3.9), sharey=True,
                             gridspec_kw={"wspace": 0.12})
    y = list(range(len(rows)))[::-1]
    bar_h = 0.56

    panels = [
        ("処理できた件数（rps、負荷中）", lambda r: (0, r[2][1]), 36,
         lambda r: f"{r[2][0]}〜{r[2][1]}" if r[2][0] != r[2][1] else f"約 {r[2][0]}"),
        ("CPU 使用率（%、負荷中）", lambda r: (r[3], r[4]), 100, lambda r: f"{r[3]:.0f}〜{r[4]:.0f}%"),
        ("スレッドの空き待ち時間の最大（秒）", lambda r: (0, r[5]), 36, lambda r: f"{r[5]:.1f}" if r[5] else "0"),
    ]
    for ax, (title, span, xmax, label) in zip(axes, panels):
        for yi, row in zip(y, rows):
            left, right = span(row)
            width = max(right - left, 0)
            ax.barh(yi, width, left=left, height=bar_h, color=row[1], edgecolor=SURFACE, linewidth=2)
            ax.text(right + xmax * 0.02, yi, label(row), va="center", ha="left", color=INK, fontsize=11)
        ax.set_title(title, loc="left", color=INK, fontsize=12, pad=10)
        ax.set_xlim(0, xmax * 1.22)
        ax.xaxis.grid(True, color=GRID, linewidth=0.8)
        ax.set_axisbelow(True)
        for side in ("top", "right", "left"):
            ax.spines[side].set_visible(False)
        ax.tick_params(axis="y", length=0)
    axes[0].set_yticks(y, [r[0] for r in rows])

    fig.suptitle("問題のある実装だけ、処理件数が落ち、CPU 使用率が下がり、待ち時間が跳ね上がる", x=0.01, ha="left",
                 fontsize=14, color=INK, y=1.04)
    fig.text(0.01, -0.06,
             "AWS Fargate cpu256（キャリアスレッド 1〜2 本）・同時 30 リクエスト × 1 秒・70 秒間。"
             "CPU は Container Insights の 1 分値で、負荷にかかる 2 点の範囲。計測 2-2",
             fontsize=9, color=INK_2, ha="left")
    fig.savefig(HERE / "claim2-cpu-inversion.png", dpi=200, bbox_inches="tight")
    plt.close(fig)


# ---------------------------------------------------------------- 仮説 1

def db_threads(filename, start_utc=None, end_utc=None):
    """DB_THREADS 行を (経過秒, 使用中の接続, DB の中で実行中のクエリ) にする。

    実行中のクエリは PROCESSLIST の active（COMMAND が Sleep 以外）から 1 を引いた値。
    アプリが何もしていないときも active は常に 1 で、DB 内部のセッションが 1 本動いているため。
    SLEEP の本数（sleeping）ではなく active を使うのは、tx-hold では SLEEP を投げないので
    sleeping は当然 0 になり、「DB の中で何も動いていない」ことの根拠にならないから。
    """
    points = []
    for line in (RAW / filename).read_text().splitlines():
        event = json.loads(line)
        t = datetime.fromtimestamp(event["t"] / 1000, tz=timezone.utc)
        if start_utc and not (start_utc <= t <= end_utc):
            continue
        fields = dict(part.split("=") for part in event["m"].split()[1:])
        points.append((t, int(fields["borrowed"]), max(int(fields["active"]) - 1, 0)))
    points.sort()
    # k6 は本計測の前に 10 同時のウォームアップを流す。使用中の本数が一度 0 に戻るまでを
    # ウォームアップとして落とし、本計測（同時 1 の段）の 3 秒前を 0 秒にする
    i = next(i for i, p in enumerate(points) if p[1] > 0)
    i = next(j for j in range(i, len(points)) if points[j][1] == 0)
    i = next(j for j in range(i, len(points)) if points[j][1] > 0)
    first = points[i][0] - timedelta(seconds=3)
    return [((t - first).total_seconds(), b, s) for t, b, s in points if t >= first]


LEVELS = [1, 5, 10, 25, 50, 100, 200, 400, 800]
# k6 の段は 30 秒ずつ。段の切り替わりに数秒の空白が入るので、実測の間隔は約 35.5 秒。
# 1 段目の中央は、db_threads() が 0 秒にした点（本計測の 3 秒前）から約 18 秒後
STAGE_SECONDS = 35.5
FIRST_STAGE_CENTER = 18.0


def claim1():
    sleep = db_threads("dbobs-sleep-db-threads.jsonl")
    tx = db_threads("m2tx-db-threads.jsonl",
                    datetime(2026, 9, 26, 17, 25, tzinfo=timezone.utc),
                    datetime(2026, 9, 26, 17, 33, tzinfo=timezone.utc))

    fig, axes = plt.subplots(1, 2, figsize=(12.5, 4.2), sharey=True, gridspec_kw={"wspace": 0.06})
    panels = [
        (axes[0], sleep, "SELECT SLEEP()：DB 側で待つ　→ 上限 約 20 rps"),
        (axes[1], tx, "tx-hold：アプリ側で待つ　→ 上限 約 117 rps"),
    ]
    centers = [FIRST_STAGE_CENTER + STAGE_SECONDS * k for k in range(len(LEVELS))]
    for ax, series, title in panels:
        xs = [p[0] for p in series]
        ax.plot(xs, [p[1] for p in series], color=FIX_CODE, linewidth=2, drawstyle="steps-post",
                label="アプリが使用中の接続")
        ax.plot(xs, [p[2] for p in series], color=BROKEN, linewidth=2, drawstyle="steps-post",
                label="DB の中で実行中のクエリ")
        ax.axhline(25, color=INK_2, linewidth=1, linestyle=(0, (4, 3)))
        ax.text(centers[-1] + STAGE_SECONDS / 2, 25.6, "プール 25 本", ha="right", va="bottom", color=INK_2, fontsize=9)
        ax.set_title(title, loc="left", color=INK, fontsize=12, pad=8)
        ax.set_xticks(centers, [str(level) for level in LEVELS])
        ax.tick_params(axis="x", length=0)
        ax.set_xlabel("同時リクエスト数（30 秒ずつ増やした）")
        ax.set_ylim(0, 29)
        ax.set_xlim(0, centers[-1] + STAGE_SECONDS / 2)
        ax.yaxis.grid(True, color=GRID, linewidth=0.8)
        ax.set_axisbelow(True)
        for side in ("top", "right"):
            ax.spines[side].set_visible(False)
    axes[0].set_ylabel("本数")
    handles, labels = axes[0].get_legend_handles_labels()
    fig.legend(handles, labels, loc="upper center", bbox_to_anchor=(0.5, -0.04), ncol=2, frameon=False, fontsize=10)

    fig.suptitle("接続は 25 本とも使用中なのに、DB の中で動いているのは 3〜4 本だけ", x=0.01, ha="left",
                 fontsize=14, color=INK, y=1.03)
    fig.text(0.01, -0.14, "mvc-virtual・プール 25・Aurora MySQL Serverless v2（2 ACU）。0.5 秒ごとに観測。"
             "実行中のクエリは、常に動いている DB 内部のセッション 1 本を除いた数",
             fontsize=9, color=INK_2, ha="left")
    fig.savefig(HERE / "claim1-db-threads.png", dpi=200, bbox_inches="tight")
    plt.close(fig)


# ---------------------------------------------------------------- RDS Proxy のセッションピニング

def proxy_series(filename, metric, first):
    """CloudWatch から書き出した RDS Proxy のメトリクスを (経過分, 1 分ごとの最大値) にする。

    ピニングの本数は 0 のときに値が出ない（データポイントがない）ので、
    ClientConnections の時刻を基準にして、欠けている分は 0 で埋める。
    """
    rows = [json.loads(line) for line in (RAW / filename).read_text().splitlines()]
    # 前の計測の名残りが入らないよう、その回の計測が始まった分（first、CloudWatch が返す JST の時刻）から使う
    base = sorted(r["t"] for r in rows if r["metric"] == "ClientConnections" and r["t"] >= first)
    values = {r["t"]: r["max"] for r in rows if r["metric"] == metric}
    start = datetime.fromisoformat(base[0])
    return [((datetime.fromisoformat(t) - start).total_seconds() / 60, values.get(t, 0.0)) for t in base]


def claim1_session_pinning():
    runs = [
        ("useServerPrepStmts=false（既定）", "proxy-pinning-prepoff.jsonl", "2026-09-26T21:14"),
        ("useServerPrepStmts=true", "proxy-pinning-prepon.jsonl", "2026-09-27T03:14"),
    ]
    fig, axes = plt.subplots(1, 2, figsize=(12.5, 3.8), sharey=True, gridspec_kw={"wspace": 0.06})
    for ax, (title, filename, first) in zip(axes, runs):
        client = proxy_series(filename, "ClientConnections", first)
        pinned = proxy_series(filename, "DatabaseConnectionsCurrentlySessionPinned", first)
        # 右のパネルでは 2 本がぴったり重なるので、青を太く下に敷き、橙を上に細く重ねる
        ax.plot([x for x, _ in client], [y for _, y in client], color=FIX_CODE, linewidth=4.5, marker="o",
                markersize=11, label="アプリから RDS Proxy への接続", zorder=2)
        ax.plot([x for x, _ in pinned], [y for _, y in pinned], color=BROKEN, linewidth=2, marker="o",
                markersize=6, label="ピニングされたセッション", zorder=3)
        ax.set_title(title, loc="left", color=INK, fontsize=12, pad=8)
        ax.set_xlabel("経過時間（分）")
        ax.set_ylim(0, 10)
        ax.yaxis.grid(True, color=GRID, linewidth=0.8)
        ax.set_axisbelow(True)
        for side in ("top", "right"):
            ax.spines[side].set_visible(False)
    axes[0].set_ylabel("本数")
    handles, labels = axes[0].get_legend_handles_labels()
    fig.legend(handles, labels, loc="upper center", bbox_to_anchor=(0.5, -0.04), ncol=2, frameon=False, fontsize=10)
    fig.suptitle("設定 1 つで、アプリの接続がすべてピニングされた", x=0.01, ha="left", fontsize=14, color=INK, y=1.04)
    fig.text(0.01, -0.2, "mvc-virtual・プール 10・tx-hold。RDS Proxy の CloudWatch メトリクス"
             "（ClientConnections、DatabaseConnectionsCurrentlySessionPinned）の 1 分ごとの最大値。\n"
             "右では 2 本の線が重なっている（アプリからの接続 7 本がすべてピニングされた）",
             fontsize=9, color=INK_2, ha="left")
    fig.savefig(HERE / "claim1-session-pinning.png", dpi=200, bbox_inches="tight")
    plt.close(fig)


if __name__ == "__main__":
    claim2()
    claim1()
    claim1_session_pinning()
