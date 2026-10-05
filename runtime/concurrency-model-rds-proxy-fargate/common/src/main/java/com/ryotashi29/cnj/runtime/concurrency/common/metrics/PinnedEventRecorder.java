package com.ryotashi29.cnj.runtime.concurrency.common.metrics;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.LongAdder;
import jdk.jfr.consumer.RecordedEvent;
import jdk.jfr.consumer.RecordingStream;

/**
 * 仮想スレッドのピニングを JFR の {@code jdk.VirtualThreadPinned} イベントとして数える。
 *
 * <p>{@link StarvationProbe} が測るのはブロックの<b>結果</b>（走り出すまでの空き待ち時間）で、
 * こちらが数えるのは<b>原因</b>（キャリアスレッドを離せないままブロックしたこと）。
 * 2 つを同じ名前空間に出すと、CloudWatch 上で原因と結果を同じ時間軸に重ねられる。
 *
 * <p><b>ファイルを書かずにメトリクスにするのが要点。</b>{@link RecordingStream} は
 * イベントをプロセス内で購読するので、Fargate のエフェメラルストレージから JFR ファイルを
 * 持ち出す必要がない。専用スレッドで回るため、観測対象のスレッドも共有しない。
 *
 * <p>同じコードが JDK によって違う結果を出すことに意味がある。{@code synchronized} の中で
 * ブロックしたとき、JDK 21 ではキャリアを離せずイベントが出るが、JDK 24 以降（JEP 491）では
 * ピニングされないのでイベントが出ない。<b>0 件は「ピニングが起きていない」であって
 * 「観測できていない」ではない</b>ため、両者を区別できるよう {@link #available()} を分けている。
 *
 * <p>粒度の制約が 1 つある。JFR はイベントをバッファに溜めてから流すので、
 * <b>発生から購読までに最大 1 秒ずれる</b>。送信間隔（デフォルト 5 秒）より短いので集計値は合うが、
 * 山の立ち上がりを 1 秒未満で読もうとしてはいけない。
 */
public final class PinnedEventRecorder implements AutoCloseable {

    /** 購読するイベント。JDK 21 にも 25 にもあるが、発火条件が違う。 */
    private static final String EVENT_NAME = "jdk.VirtualThreadPinned";

    private final long thresholdMillis;
    private final LongAdder eventCount = new LongAdder();
    private volatile long maxDurationMillis;
    private volatile RecordingStream stream;
    private volatile String status = "未開始";

    /**
     * @param thresholdMillis この時間より短いピニングは数えない。JFR のデフォルトと同じ 20 ms を想定。
     *                        0 にすると極短時間のピニングまで拾って件数が意味を失うので、
     *                        「害のある長さ」に合わせて設定する
     */
    public PinnedEventRecorder(long thresholdMillis) {
        this.thresholdMillis = thresholdMillis;
    }

    /**
     * 購読を開始する。
     *
     * <p>JFR が使えない実行環境（イベントを持たない JDK、jlink でモジュールを削ったランタイム）でも
     * 計測そのものは続けたいので、失敗しても例外を投げず {@code false} を返す。
     * 呼び出し側はこの戻り値でメトリクスを登録するかどうかを決める。
     *
     * @return 購読を開始できたか
     */
    public boolean start() {
        try {
            RecordingStream recordingStream = new RecordingStream();
            // 順序は要らない。件数と最長が知りたいだけなので、並べ替えのコストを払わない
            recordingStream.setOrdered(false);
            recordingStream.enable(EVENT_NAME)
                    .withThreshold(Duration.ofMillis(thresholdMillis))
                    // スタックトレースは取らない。どのコードがピニングしたかは条件名で分かっており、
                    // 計測中のプロセスにスタックウォークのコストを載せたくない
                    .withoutStackTrace();
            recordingStream.onEvent(EVENT_NAME, this::onEvent);
            recordingStream.onError(error -> status = "エラー: " + error);
            recordingStream.startAsync();
            this.stream = recordingStream;
            this.status = "稼働中";
            return true;
        } catch (RuntimeException | LinkageError e) {
            // LinkageError も拾うのは、jdk.jfr を含まないランタイムで動かしたときに
            // NoClassDefFoundError で起動ごと落とさないため
            this.status = "利用不可: " + e;
            return false;
        }
    }

    /** 購読できているか。{@code false} のときの件数 0 は「観測していない」の意味。 */
    public boolean available() {
        return stream != null;
    }

    /**
     * 前回呼ばれてからのピニング件数を返し、カウンタを戻す。
     *
     * <p>累計ではなく区間の件数を出すのは、EMF の 1 行を 1 点として扱うため。
     * CloudWatch 側で {@code Sum} を取れば任意の区間の合計に戻せる。
     */
    public long takeEventCount() {
        return eventCount.sumThenReset();
    }

    /** 前回呼ばれてから観測した、最も長いピニングの時間。 */
    public long takeMaxDurationMillis() {
        long value = maxDurationMillis;
        maxDurationMillis = 0;
        return value;
    }

    /**
     * 診断エンドポイント用の申告。
     *
     * <p>{@code available} と {@code status} を返すのは、<b>「ピニングが起きていない」と
     * 「観測できていない」を外から区別するため</b>。件数だけを見せると、購読に失敗した条件を
     * 「健全だった」と読み違える。
     */
    public Map<String, Object> snapshot() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("event", EVENT_NAME);
        result.put("available", available());
        result.put("status", status);
        result.put("thresholdMillis", thresholdMillis);
        result.put("pendingEventCount", eventCount.sum());
        result.put("pendingMaxDurationMillis", maxDurationMillis);
        return result;
    }

    @Override
    public void close() {
        RecordingStream recordingStream = this.stream;
        this.stream = null;
        this.status = "停止";
        if (recordingStream != null) {
            recordingStream.close();
        }
    }

    /** JFR の購読スレッドから呼ばれる。カウンタを進めるだけで、重い処理は置かない。 */
    private void onEvent(RecordedEvent event) {
        record(event.getDuration());
    }

    /** 集計の本体。イベントの入手経路と分けてあるのは、テストから時間だけを渡せるようにするため。 */
    void record(Duration duration) {
        eventCount.increment();
        long millis = duration == null ? 0 : duration.toMillis();
        if (millis > maxDurationMillis) {
            maxDurationMillis = millis;
        }
    }
}
