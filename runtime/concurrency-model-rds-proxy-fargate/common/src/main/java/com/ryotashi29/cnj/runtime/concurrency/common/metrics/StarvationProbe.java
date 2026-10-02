package com.ryotashi29.cnj.runtime.concurrency.common.metrics;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 少数のスレッドがブロックされていることを、CPU ではなく<b>遅延</b>で測る。仮説 2 の対策のうち「観測」。
 *
 * <p>やっていることは単純で、監視対象の executor に何もしないタスクを投げ、
 * <b>投げてから走り出すまでの時間</b>を測る。イベントループやキャリアスレッドがブロックされていれば、
 * この時間が伸びる。計算していないスレッドを待たせても CPU 使用率は上がらないため、
 * {@code CpuUtilized} では見えない枯渇がここには現れる。
 *
 * <p><b>タスクが「走らないこと」を扱えるのが要点。</b>完全にブロックされた executor では投げたタスクが
 * 永遠に走らないので、「走り出すまでの時間」を完了時にだけ記録すると<b>標本がゼロになり、
 * 一番知りたい瞬間の値が欠ける</b>。そのため未完了の間は {@code いま - 投げた時刻} を
 * 現在値として返す。ブロックされている間は値が単調に伸びる。
 *
 * <p>監視は専用のプラットフォームスレッドで行い、監視対象の executor には一切乗らない。
 * k6 をプロセス外に置いたのと同じ理由で、<b>観測者は観測対象の資源を共有してはならない</b>。
 * 同じ理由で、前のタスクが完了するまで次を投げない（ブロックされた executor に積み増すと、
 * 観測のためにキューを溢れさせることになる）。
 */
public final class StarvationProbe implements AutoCloseable {

    /** 走り出すまでの時間の単位。ミリ秒未満の揺れは結論に影響しないので ms で丸める。 */
    private static final long NANOS_PER_MILLI = 1_000_000L;

    private final String resourceName;
    private final List<Watched> watched;
    private final long intervalMillis;
    private final ScheduledExecutorService watcher;
    private volatile long highWaterMillis;

    /**
     * @param resourceName 監視対象の呼び名。{@code carrier} や {@code event-loop}
     * @param targets      監視対象。ラベルと executor の対。イベントループのように複数本あるものは全部渡す
     * @param intervalMillis 監視の間隔
     */
    public StarvationProbe(String resourceName, Map<String, Executor> targets, long intervalMillis) {
        this.resourceName = resourceName;
        this.intervalMillis = intervalMillis;
        this.watched = new ArrayList<>();
        targets.forEach((label, executor) -> this.watched.add(new Watched(label, executor)));
        this.watcher = Executors.newSingleThreadScheduledExecutor(runnable -> {
            // 仮想スレッドにはしない。キャリアスレッドを監視するのに、
            // 自分がキャリアスレッドに乗っていてはブロックされたときに動けない
            Thread thread = new Thread(runnable, "starvation-probe-" + resourceName);
            thread.setDaemon(true);
            return thread;
        });
    }

    public void start() {
        watcher.scheduleAtFixedRate(this::tick, 0, intervalMillis, TimeUnit.MILLISECONDS);
    }

    /** 監視対象のうち最も遅れているものの、いまの遅れ。 */
    public long lagMillis() {
        long now = System.nanoTime();
        long max = 0;
        for (Watched target : watched) {
            max = Math.max(max, target.lagMillis(now));
        }
        return max;
    }

    /**
     * 前回呼ばれてから観測した遅れの最大値を返し、高水位をリセットする。
     *
     * <p>メトリクスの送信間隔（数秒）より監視の間隔（数百ミリ秒）のほうが短いので、
     * 送信時点の瞬間値だけを送ると山を取り逃す。送信側はこちらを使う。
     */
    public long takeMaxLagMillis() {
        long value = Math.max(highWaterMillis, lagMillis());
        highWaterMillis = 0;
        return value;
    }

    /**
     * 診断エンドポイント用の内訳。
     *
     * <p>{@code runnerThread} を返すのは、<b>狙ったスレッドを本当に監視できているかを
     * 推測せず確かめるため</b>。WebFlux 側はサーバが使っているイベントループ群を
     * Spring の {@code ReactorResourceFactory} から辿っているので、ここが
     * {@code reactor-http-nio-*} になっていることが前提の成立条件になる。
     */
    public Map<String, Object> snapshot() {
        long now = System.nanoTime();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("resource", resourceName);
        result.put("lagMillis", lagMillis());
        List<Map<String, Object>> targets = new ArrayList<>();
        for (Watched target : watched) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("target", target.label);
            entry.put("lagMillis", target.lagMillis(now));
            entry.put("pending", target.pending);
            entry.put("runnerThread", target.lastRunnerThread);
            targets.add(entry);
        }
        result.put("targets", targets);
        return result;
    }

    @Override
    public void close() {
        watcher.shutdownNow();
    }

    private void tick() {
        for (Watched target : watched) {
            target.submitIfIdle();
        }
        highWaterMillis = Math.max(highWaterMillis, lagMillis());
    }

    /**
     * 監視対象 1 本ぶんの状態。
     *
     * <p>書き手は監視スレッドと監視対象スレッドの 2 つ、読み手はさらに別（HTTP スレッドや
     * 送信スレッド）なので volatile で足りる。値の厳密な一貫性は要らない。
     * 知りたいのは「数ミリ秒か、数千ミリ秒か」という桁であって、1 ミリ秒の正確さではない。
     */
    private static final class Watched {

        private final String label;
        private final Executor executor;
        private volatile long submittedAtNanos;
        private volatile boolean pending;
        private volatile long lastLagNanos;
        private volatile String lastRunnerThread = "(未観測)";

        private Watched(String label, Executor executor) {
            this.label = label;
            this.executor = executor;
        }

        private void submitIfIdle() {
            if (pending) {
                // まだ走っていないタスクがある。積み増さずに待つ
                return;
            }
            submittedAtNanos = System.nanoTime();
            pending = true;
            try {
                executor.execute(this::onRun);
            } catch (RejectedExecutionException e) {
                // 停止中の executor。遅延ではないので未観測に戻す
                pending = false;
                lastRunnerThread = "(拒否: " + e.getClass().getSimpleName() + ")";
            }
        }

        private void onRun() {
            lastLagNanos = System.nanoTime() - submittedAtNanos;
            Thread current = Thread.currentThread();
            // 仮想スレッドは toString() にキャリアスレッド名まで入る。
            // 「どのキャリアに乗って走れたか」が知りたいので、そちらを採る
            lastRunnerThread = current.isVirtual() ? current.toString() : current.getName();
            // 最後に下ろす。先に下ろすと、投げ直しと計測値の書き込みが競合する
            pending = false;
        }

        private long lagMillis(long nowNanos) {
            // 未完了なら「まだ走っていない時間」が遅れの下限。完了済みなら測れた値
            long nanos = pending ? nowNanos - submittedAtNanos : lastLagNanos;
            return Math.max(0, nanos) / NANOS_PER_MILLI;
        }
    }
}
