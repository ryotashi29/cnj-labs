# 検証の設計

[README](../README.md) の結果を読むための詳しい背景です。スレッドの役割、ワークロードの選び方、観測の仕組み、JDK による違い、手元での動かし方をまとめています。

## スレッドの本数は、何のための本数かで決まる

仮説 2 を読むには、Tomcat の「200 本」とキャリアスレッドの「CPU 数ぶん」が、**違う量を数えている**ことを押さえる必要があります。
OS のスレッドは 1 コアに何百本でも置けます。本数の違いは、何のために用意したかの違いです。

| | 何のための本数か | ブロックしてよいか |
|---|---|---|
| Tomcat の `maxThreads=200` | **同時実行数**（同時にいくつのリクエストを受け持つか） | よい。200 本のうち 199 本が DB 待ちで寝ていても、寝ているスレッドは CPU を使わない |
| キャリアスレッド = CPU 数 | **並列実行数**（同時にいくつのコードを実際に動かすか） | いけない。仮想スレッドは「待つときはキャリアから降りる」前提なので、キャリアは CPU 数だけあれば足りる。降りられないと全体が止まる |

条件ごとに、実際にどのスレッドが動いているかです。

| 条件 | 接続を受け付けるスレッド | コードが動くスレッド | 本数 | ブロックしてよいか |
|---|---|---|---|---|
| `mvc-platform` | `http-nio-8080-Poller` 1〜2 本 | `http-nio-8080-exec-N` | 200 | よい |
| `mvc-virtual` | `http-nio-8080-Poller` 1〜2 本 | 仮想スレッド（キャリアは `ForkJoinPool-1-worker-N`） | 仮想スレッドは事実上無制限、キャリアは CPU 数 | 仮想スレッドはよい。キャリアはいけない |
| `webflux` | `reactor-http-nio-N` | 同じ `reactor-http-nio-N` | `max(4, CPU 数)` | いけない |

- Tomcat は、接続を受け付けるスレッド（少数、ブロックしてはいけない）と処理するスレッド（200 本、ブロックしてよい）を分けています。
  だから 200 本が全部 DB 待ちでも、受け付けは続きます。
- WebFlux はこの 2 つが同じスレッドなので、処理でブロックすると受け付けも止まります。
- 仮想スレッドはその中間です。受け付けは分かれていますが、処理はキャリアを共有します。降りられれば問題なく、降りられないと止まります。

**仮想スレッドのピニングと、イベントループのブロックは同じ形の失敗です。** どちらも、CPU 数ほどしかないスレッドをブロックしたことが原因です。
ただし直し方は違います。

- 仮想スレッドは、処理の途中の状態を保存してキャリアから降りられます。JDK 24 の [JEP 491](https://openjdk.org/jeps/491) は、`synchronized` の中でも降りられるようにしました。
- WebFlux のイベントループで動く処理は、途中で降りる仕組みを持ちません。JDK では直せず、書く人が `subscribeOn` / `publishOn` で別のスレッドに移すしかありません。

キャリアスレッドの本数「CPU 数」は目標値で、厳密な上限ではありません。
`ForkJoinPool` はブロックを検出すると、最大 256 本まで一時的に増やして埋め合わせることがあります。
ただし計測 1b では、`LiveThreads`（生きているスレッド数）は 26 本のまま動かず、この埋め合わせは起きていませんでした。

## ワークロード

保持時間は `ms` で揃えます（既定 200 ms、上限 5000 ms）。

| エンドポイント | 内容 | mvc | webflux | 役割 |
|---|---|---|---|---|
| `/api/tx-hold` | トランザクションを開始 → `SELECT CONNECTION_ID()` → **アプリ側で待つ** → コミット | ○ | ○ | 接続だけを保持する。仮説 1 の本体 |
| `/api/db` | `SELECT SLEEP()` → `SELECT CONNECTION_ID()` | ○ | ○ | DB 側で待つ。接続に加えて DB の中の処理も占有する |
| `/api/nodb` | DB を使わずに同じ時間待つ | ○ | ○ | 基準。モデル自体の処理能力を示す |
| `/api/bounded` | セマフォで同時実行数を制限する。空きがなければ最大 0.2 秒待ち、それでも空かなければ 503 | ○ | ○ | 接続待ちでタイムアウトさせる代わりに、入り口で 503 を返す |
| `/api/pinned` | `synchronized` の中で待つ | ○ | − | 仮説 2 の問題あり。JDK 21 でキャリアをピニングする |
| `/api/pinned-lock` | 同じ処理を `ReentrantLock` の中で待つ | ○ | − | 仮説 2 の対策①。`/api/pinned` との差は 1 行 |
| `/api/blocking` | イベントループ上で JDBC を呼ぶ | − | ○ | 仮説 2 の問題あり |
| `/api/blocking-isolated` | 同じ JDBC 呼び出しを `boundedElastic` 上で呼ぶ | − | ○ | 仮説 2 の対策。`/api/blocking` との差は 1 行 |
| `/api/diagnostics` | CPU 数、プールの設定、スレッドの空き待ち時間、ピニングの件数、DB の中の処理数などを返す | ○ | ○ | 計測条件をアプリ自身に報告させる |

### `SELECT SLEEP()` と `tx-hold`

当初は `SELECT SLEEP()` を主役にしていました。DB の CPU を使わずに接続だけを占有でき、保持時間を正確に決められると考えたからです。
ところが Aurora MySQL では、`SLEEP` は **DB の中の処理も占有**していました。
接続 25 本に対し、同時に実行される `SLEEP` は 2 ACU で 3〜4 本しかなく、プールより先にそちらが上限になりました（[README の計測 1-1](../README.md#計測-1-1上限はコネクションプールで決まるか)）。

そこで `tx-hold` を足しました。トランザクションの中で接続を保持したまま、アプリ側で待ちます。待っている間、DB には何も送らないので、占有するのは接続だけです。
これは「トランザクションの中で外部 API を呼んで、接続を保持したまま待つ」という、現場でよくある失敗の再現でもあります。

トランザクションにしているのは RDS Proxy のためです。トランザクションの外で待つと、その間に Proxy がバックエンドの接続を他のリクエストに回してしまい、接続を保持したことになりません。

`/api/db` は残しています。「DB の中にも上限がある」ことを示すのに使います。

### 公平に比べるための前提

- **接続の本数を揃える。** HikariCP の `maximumPoolSize` と r2dbc-pool の `maxSize` は、どちらも同じ `POOL_SIZE` を読みます。`/api/diagnostics` が実際の値を返すので、結果と一緒に確かめられます。
- **共通の処理は `common` モジュールに置く。** 保持時間の既定値、レスポンスの形、メモリの測り方が 2 つのアプリで違うと、モデルの差ではなく実装の差を測ってしまいます。
- **MVC と WebFlux は別の jar にする。** 1 つの jar だと、どちらのスタックが起動したのかが曖昧になります。
- **WebFlux の同時実行制限は自作の `AsyncSemaphore`。** `Semaphore` を `boundedElastic` 上で待たせると、その上限（CPU 数 × 10）で詰まり、測りたいものが測れません。
  許可の取りこぼしは結果に静かに出る（スループットが少しずつ落ちる）ので、`Mono.usingWhen` で取得と返却を対にし、テストで確かめています。

## 観測の仕組み

アプリの中に 4 つの観測用のクラスを置いています（`common/src/main/java/.../common/metrics/`）。

| クラス | 何を測るか |
|---|---|
| `StarvationProbe` | **スレッドの空き待ち時間。** 監視するスレッド（キャリアまたはイベントループ）に何もしない仕事を 250 ms ごとに投げ、走り出すまでの時間を測る。まだ走っていなければ「いま − 投げた時刻」を返す |
| `PinnedEventRecorder` | **仮想スレッドのピニングの件数。** Java の記録機能（JFR）の `jdk.VirtualThreadPinned` を、アプリの中で購読して数える。20 ms 未満は数えない |
| `DatabaseThreadObserver` | **DB の中で実行中の処理数。** アプリのプールとは別の 1 本の接続で、0.5 秒ごとに `information_schema.PROCESSLIST` を読む。接続先は RDS Proxy を通さず Aurora に直結 |
| `EmbeddedMetricsPublisher` | 上の値を 5 秒ごとに [EMF](https://docs.aws.amazon.com/AmazonCloudWatch/latest/monitoring/CloudWatch_Embedded_Metric_Format_Specification.html)（CloudWatch 用の JSON の形式）で標準出力に書く。CloudWatch Logs がメトリクスに変換する |

実装で外せない点です。間違えると「問題は起きていない」という誤った結果が出ます。

1. **走らない仕事も待ち時間として数える。** 完全にブロックされていると、投げた仕事は永遠に走りません。走ったときにだけ記録すると、一番知りたい瞬間の値が空になります
2. **観測する側は、観測されるスレッドに乗らない。** 監視と送信はそれぞれ専用の従来のスレッドで動かします。観測用の DB 接続も、アプリのプールから借りません
3. **観測できないときは値を出さない。** JFR や DB に接続できないときに 0 を出すと、「起きていない」と読めてしまいます。出さなければ集計は `n/a` になり、0 と区別できます
4. **どのスレッドを監視しているかを報告させる。** `/api/diagnostics` の `runnerThread` で、狙ったスレッドを監視できているかを推測でなく値で確かめます

EMF にしたのは、追加のライブラリも IAM の権限も CDK の変更も要らないからです。標準出力はもともと CloudWatch Logs に送っているので、書式を合わせるだけでメトリクスになります。

**アプリの中の観測は、アプリ自身が止まると報告も止まります。** 手元で測ると、`/api/diagnostics` 自身の応答が、普段 0.003 秒のところ、キャリアがブロックされている間は 5 秒かかりました。
だから計測では、止まったことの検知はアプリの外（負荷とは別に送る軽いリクエスト）で行い、アプリの中の値は「どこが詰まったか」の切り分けに使っています。

### JFR の 2 つの使い方

- **ファイルに記録して、あとで見る。** Fargate ではタスクが消えるとファイルも消えるので、`jcmd JFR.dump` → S3 のような持ち出しが要ります。JRE だけの軽いイメージには `jcmd` が入っていません。調査用で、アラートには使えません
- **アプリの中でイベントを購読して、件数にする。** `jdk.jfr.consumer.RecordingStream`（JDK 14 以降）を使えば、ファイルを書かずにイベントを受け取れます。このラボはこちらです

```java
stream = new RecordingStream();
stream.enable("jdk.VirtualThreadPinned").withThreshold(Duration.ofMillis(20));
stream.onEvent("jdk.VirtualThreadPinned", event -> pinnedCounter.increment());
stream.startAsync();
```

ブロックを調べるなら、`jdk.VirtualThreadPinned` / `jdk.ThreadPark` / `jdk.JavaMonitorEnter` / `jdk.SocketRead` が役に立ちます。
`jdk.ExecutionSample`（普段のフレームグラフの元データ）は、実行中のスレッドしか記録しないので、ブロックされたスレッドは映りません。
async-profiler を使う場合も、既定の `-e cpu` ではなく `-e wall` を使います。

## 本番で考えること

このラボで確かめたことから言える、運用上の注意です（ロードバランサのない構成で測っているので、一部は推測です）。

- **CPU 使用率でスケールさせると、この停止には反応しない。** CPU は下がるので、設定によってはスケールインします
- **アプリが自分で「詰まっている」ことを報告する必要がある。** スレッドの空き待ち時間やプールの待ち数など、CPU では見えない値をメトリクスとして出します
- **止まったことの検知は、アプリの外から行う。** CloudWatch Synthetics などで軽いエンドポイントを定期的に呼び、応答時間でアラームにします
- **ヘルスチェックに頼りすぎない。** ヘルスチェックの失敗でタスクが外れると、残ったタスクに負荷が集まり、そちらも詰まります（推測）。原因が全体に共通なら、新しいタスクも同じように詰まります
- **証拠はタスクと一緒に消える。** 終了のシグナルを受けたときにスレッドダンプを標準出力に書き出しておけば、CloudWatch Logs に残ります

AWS で実際にブロックの原因になりやすいもの（コードレビューでは見落としやすいものです）。

- AWS SDK v1 や、SDK v2 の同期クライアント（`S3Client` と `S3AsyncClient` は名前が 1 語しか違いません）
- 認証情報の更新（STS / IMDS）。普段は起きず、期限が来たときに起きます
- DNS の名前解決。Aurora や RDS Proxy はフェイルオーバーで DNS が変わります
- ログの同期出力。CloudWatch Logs 側が詰まると、標準出力への書き込みがブロックします

## JDK による違い

| | JDK 21 | JDK 25 |
|---|---|---|
| 仮想スレッド + `synchronized` の中で待つ | ピニングが起きる | **起きない**（JEP 491） |
| WebFlux のイベントループでブロックする | 起きる | **起きる** |

- 計測の中心は JDK 25 です。読者が実際に使うバージョンで測るためです。
- `mvc-virtual-jdk21` だけは JDK 21 で動かします。JDK 25 では `synchronized` によるピニングが起きないので、問題ありの条件を作るのに JDK 21 が必要です。
- **同じ jar を JDK 21 と 25 で動かしています。** ビルドの対象は Java 21 に固定しています（Spring Boot 4 の要件は Java 17 以上）。
  コードもフラグも同じで JDK だけ変えると、ピニングは 130 件 → 0 件になりました。
- JDK 25 でも、JNI などのネイティブコードを呼んでいる最中はピニングが残ります。これは試していません。
- 手元の既定の `java` が JDK 25 だと、`/api/pinned` を流しても何も起きません。コードは正しいので気づけず、「ピニングは問題ない」と誤解しやすい点に注意してください。
  `k6/sweep.sh` は、JDK 24 以降のときに `pinned` を除外し、そのことを表示します。

ピニングを調べる方法です。

```bash
# JDK 21。ピニングが起きた場所のスタックを出す（JDK 22 以降は非推奨）
java -Djdk.tracePinnedThreads=full -jar mvc/build/libs/mvc-0.0.1-SNAPSHOT.jar

# JFR で jdk.VirtualThreadPinned を記録する
java -XX:StartFlightRecording=filename=vt.jfr,settings=profile -jar mvc/build/libs/mvc-0.0.1-SNAPSHOT.jar
```

### Spring Boot 4 / Gradle 9 への更新

計測の前に Spring Boot 3.4.0 → 4.1.1、Gradle 8.11.1 → 9.7.1、`mybatis-spring-boot-starter` 3.0.4 → 4.1.0 に上げました。
「バージョンを上げる」話をするのに、検証環境が古いままでは説得力がないためです。

つまずいたのは 1 点だけです。Boot 4 は自動設定をモジュールに分けたため、`spring-r2dbc` だけでは R2DBC の接続が自動で作られず、WebFlux が起動時に
`No qualifying bean of type 'io.r2dbc.spi.ConnectionFactory'` で落ちました。`org.springframework.boot:spring-boot-r2dbc` を足して直しました。
**コンパイルは通るのに起動で落ちる**ので、更新したら起動まで確かめる必要があります。

## 手元で動かす

```bash
docker compose up -d                                  # MySQL（ホスト側は 3307）
JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew build

./gradlew :mvc:bootRun                                # Spring MVC  http://localhost:8080
./gradlew :webflux:bootRun                            # WebFlux     http://localhost:8080（同時には起動できない）

curl 'localhost:8080/api/tx-hold?ms=200'
curl localhost:8080/api/diagnostics                   # webStack でどちらが動いているか分かる
```

- ビルドには JDK 21 が必要です（toolchain が 21 のため）。Gradle 自体は 25 でも動きます。
- DB の中の処理数を観測するときは `DB_OBSERVER_HOST=localhost` を付けて起動します。

### 条件を一巡する

仮想スレッドの有効・無効は起動時にしか切り替えられず、MVC と WebFlux は別の jar なので、条件ごとにアプリを起動し直す必要があります。
取り違えを防ぐため、ビルドから計測までをスクリプトにしています。

```bash
./k6/sweep.sh                                          # 4 条件を一巡
CONDITIONS="mvc-virtual webflux-r2dbc" ./k6/sweep.sh   # 条件を絞る
MODES="tx-hold nodb" POOL_SIZES="5 10 25" ./k6/sweep.sh
```

ブロックの影響を見るときはこちらです。

```bash
./k6/crosstalk.sh                       # webflux。/api/blocking で負荷をかけ、/api/nodb を観測する
LOAD_MODE=db ./k6/crosstalk.sh          # 比較用。正しい R2DBC の実装では影響が出ない
LOAD_MODE=pinned ./k6/crosstalk.sh      # mvc-virtual に同じことをする
```

結果は `k6/results/` に出ます（git 管理外）。

### 主な設定

どれも環境変数で上書きできます。

| 変数 | 既定 | 意味 |
|---|---|---|
| `VIRTUAL_THREAD_ENABLED` | `false` | MVC で仮想スレッドを使うか |
| `POOL_SIZE` | `10` | コネクションプールの最大本数。HikariCP と r2dbc-pool が同じ値を読む |
| `POOL_CONNECTION_TIMEOUT_MS` | `5000` | 接続待ちの上限 |
| `TOMCAT_MAX_THREADS` | `200` | 仮想スレッドを使わないときの Tomcat のスレッド数 |
| `WORKLOAD_PERMITS` | `10` | `/api/bounded` の同時実行の上限 |
| `SERVER_PREP_STMTS` | `false` | サーバ側プリペアドステートメント。RDS Proxy のセッションピニングを起こす。JDBC にだけ効き、R2DBC には効かない |
| `DB_OBSERVER_HOST` | （空） | DB の中の処理数を観測する接続先。空なら観測しない |
| `STARVATION_PROBE_INTERVAL_MS` | `250` | スレッドの空き待ち時間を測る間隔 |
| `STARVATION_PUBLISH_INTERVAL_MS` | `5000` | EMF を出す間隔。この間の最大値を出す |
| `STARVATION_PINNED_THRESHOLD_MS` | `20` | ピニングとして数える最短の時間 |
| `METRICS_CONDITION` | `unknown` | メトリクスに付ける条件名 |

### 手元での結果（参考）

手元（11 コアの macOS）では CPU が多く、キャリアスレッドやイベントループが 1 本しかない状態を作れません。
そこで並列度を 1 に絞り（`-Djdk.virtualThreadScheduler.parallelism=1`、`reactor.netty.ioWorkerCount=1`）、計測の仕組みが動くことを確かめました。
Spring Boot 3.4 のときの値も含むので、AWS の結果と並べて比べないでください。

| 条件（手元、並列度 1） | 待ち時間のピーク | 走ったスレッド |
|---|---|---|
| `webflux`、`/api/blocking` × 6 | 3,920 ms | `reactor-http-nio-1` |
| `webflux`、`/api/blocking-isolated` | 0 ms | `boundedElastic-2` |
| `mvc-virtual`（JDK 21）、`/api/pinned` | 5,953 ms | （走れず） |
| `mvc-virtual`（JDK 21）、`/api/pinned-lock` | 0 ms | `VirtualThread[...]@ForkJoinPool-1-worker-1` |

同じ jar を JDK だけ変えて、キャリア 1 本で `/api/pinned?ms=1000` を 5 本同時に送った結果です。

| JDK | 5 本の応答時間 | ピニング |
|---|---|---|
| 21.0.11 | 1.07 / 2.08 / 3.08 / 4.09 / 5.10 秒（1 本ずつ順番に処理） | 5 件（最長 1,005 ms） |
| 25.0.3 | 1.02〜1.04 秒（同時に処理） | 0 件 |

RDS Proxy のセッションピニングを起こす設定が、実際に通信を変えていることも手元で確かめました。
`useServerPrepStmts=true` にすると、MySQL の `Com_stmt_prepare` / `Com_stmt_execute` が `/api/db` 10 回で 2 → 22 に増えます。

## この検証で扱っていないこと

- **ブロックをテストで見つける仕組み（BlockHound）。** このラボはわざとブロックする条件を用意しているので、入れると条件そのものが落ちます。本番のコードでは逆で、入れたほうがよいものです
- **ピニング以外のブロックの場所の特定。** どの行でブロックしたかは記録していません。条件の名前で原因が分かっているためです
- **CPU の割り当ての制限（CFS スロットリング）。** 負荷のないときの軽いリクエストの p99 が 45〜88 ms と大きく、0.25 vCPU の制限の影響とも考えられますが、GC や JIT でも同じ形になります。確かめるには `/sys/fs/cgroup/cpu.stat` を見る必要があります
- **管理用のポートを分ける効果。** MVC は `management.server.port` を分ければ別の受け付けになりますが、WebFlux で同じ効果があるかは試していません
