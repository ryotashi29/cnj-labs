# 計測結果

AWS 上で計測した結果の表。生ログ（`k6/results/`）は追跡しないので、ここにあるのは
生ログとコンソール出力から**表の部分だけを抜き出したもの**。
ファイル名の `measure1`〜`measure5` は計測した当時の番号で、README では仮説ごとに番号を振り直しています（下の表の「README」の列）。計測の読み方は [docs/aws.md](../docs/aws.md#結果の読み方) を参照。

| ファイル | README | 内容 |
|---|---|---|
| [`aws-summary.md`](aws-summary.md) | — | 全計測の一覧（条件・DB 経路・ピニング・スレッドの空き待ち時間・RSS）。`measure-aws.sh` が 1 段ごとに 1 行足す |
| `tables/2026-09-21-measure1-*.md` | 2-1 | 計測 2-1（スレッドをブロックしたときの影響、crosstalk / cpu256） |
| `tables/2026-09-24-measure1b-*.md` | 2-2 | 計測 2-2（対策の効果）。問題のある実装と対策後を同じイメージで計測した 5 条件 |
| [`tables/2026-09-24-scenario-bounded-nodb.md`](tables/2026-09-24-scenario-bounded-nodb.md) | 1-1 | 3 モデルの `bounded`（同時 10 まで、あふれたら 503）と `nodb`。同時 800 でもエラー 0 件、p50 0.2〜0.8 秒 |
| [`tables/2026-09-25-measure2-sleep.md`](tables/2026-09-25-measure2-sleep.md) | 1-1 | 計測 1-1 の 1 回目（`MODES=db`、`SELECT SLEEP()`）。**プールを増やしても上限が 約 20 rps で動かなかった**回 |
| [`tables/2026-09-25-measure3-4-sleep.md`](tables/2026-09-25-measure3-4-sleep.md) | 1-3・1-4 | 計測 1-3・1-4 の 1 回目（同じく SLEEP）。direct でも上限が変わらず、原因を DB 側に絞った |
| [`tables/2026-09-25-sleep-acu8.md`](tables/2026-09-25-sleep-acu8.md) | 1-1 | ACU を 8 に固定した SLEEP。上限が 30〜35 rps に上がり、Aurora の容量に左右されることを確かめた |
| [`tables/2026-09-26-db-threads-serverless-2acu.md`](tables/2026-09-26-db-threads-serverless-2acu.md) | 1-2 | 使用中の接続と、DB 内で実行中の SLEEP の本数（Serverless 2 ACU）。**25 本使用中でも 3〜4 本しか実行されない** |
| [`tables/2026-09-26-db-threads-t4g-medium.md`](tables/2026-09-26-db-threads-t4g-medium.md) | 1-2 | 同じ観測を通常インスタンス db.t4g.medium で。Serverless 特有ではない |
| `tables/2026-09-2{6,7}-measure2-tx-hold-part{1,2}.md` | 1-1・1-2 | 計測 1-1 の計測し直し（`MODES=tx-hold`、接続だけを保持する）。**上限がプールに比例する**。part 1 は 6 段目でネットワーク断により中断、残りを part 2 で計測した |
| [`tables/2026-09-27-measure3-4-tx-hold.md`](tables/2026-09-27-measure3-4-tx-hold.md) | 1-3・1-4 | 計測 1-3・1-4 の計測し直し（tx-hold）。Proxy は上限を変えず、応答が 約 10 ms 延びるだけ。ピニングは起きるが、アプリ 1 台では上限に影響しない |
| [`tables/2026-09-27-measure5-task-size.md`](tables/2026-09-27-measure5-task-size.md) | 1-5 | 計測 1-5（タスクサイズ cpu256〜cpu2048）。**DB を使わない処理の上限は CPU に合わせて伸び、tx-hold の上限はプール 10 本で 約 47 rps のまま動かない** |

記事用の図は [`docs/figures/`](../docs/figures/)、CloudWatch ダッシュボードの定義は
[`docs/dashboard.json`](../docs/dashboard.json)。

| 図 | 内容 |
|---|---|
| [`claim2-cpu-inversion.png`](../docs/figures/claim2-cpu-inversion.png) | 仮説 2。問題のある実装だけ、処理件数が落ち、CPU 使用率が下がり、待ち時間が跳ね上がる |
| [`claim1-db-threads.png`](../docs/figures/claim1-db-threads.png) | 仮説 1。接続は 25 本とも使用中なのに、DB 内で実行されているのは 3〜4 本だけ |
| [`dashboard-claim2.png`](../docs/figures/dashboard-claim2.png) | 仮説 2 を CloudWatch の画面で見たもの（1 分粒度） |
| [`dashboard-claim1.png`](../docs/figures/dashboard-claim1.png) | 仮説 1 を CloudWatch の画面で見たもの（1 分粒度） |
