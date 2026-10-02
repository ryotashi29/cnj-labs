package com.ryotashi29.cnj.runtime.concurrency.common.metrics;

import java.util.List;
import java.util.StringJoiner;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * カスタムメトリクスを EMF（CloudWatch Embedded Metric Format）として stdout に 1 行で吐く。
 *
 * <p>Fargate の stdout は awslogs で CloudWatch Logs に運ばれ、<b>CloudWatch Logs 側が EMF を
 * 自動でメトリクスに変換する</b>。そのため {@code cloudwatch:PutMetricData} の権限も
 * 追加の依存ライブラリも要らず、Container Insights の {@code CpuUtilized} と同じグラフに
 * 重ねられる。仮説 2 の「CPU は低いままアプリ全体が応答しなくなる」を 1 枚の図で示すのがこれの目的。
 *
 * <p><b>{@code PutMetricData} を使わないのは権限の都合だけではない。</b>送信そのものが
 * 詰まったプロセスの中で動くため、スレッドが枯渇している最中に送信スレッドも巻き込まれて
 * <b>一番知りたい瞬間の値が欠ける</b>。stdout への書き込みなら awslogs（プロセス外）が運ぶので、
 * 巻き込まれる範囲が狭い。それでも無関係ではなく、CloudWatch Logs がスロットルすると
 * stdout 自体がブロックしうる。だから検知の主役はあくまでプロセス外の probe に置く。
 *
 * <p>ロガーを通さず {@code System.out} に直接書くのは、Logback のパターンが前置されると
 * EMF として解釈されなくなるため。{@code println} 1 回で 1 行を完結させているので、
 * 同じ {@code System.out} に書く Logback と行が混ざることはない。
 */
public final class EmbeddedMetricsPublisher implements AutoCloseable {

    /**
     * 1 つのメトリクス。
     *
     * @param name  CloudWatch 上の名前
     * @param unit  CloudWatch の単位名（{@code Milliseconds} など）
     * @param value 送信時に評価される値
     */
    public record Metric(String name, String unit, Supplier<Number> value) {
    }

    private final String namespace;
    private final String dimensionName;
    private final String dimensionValue;
    private final List<Metric> metrics;
    private final long intervalMillis;
    private final ScheduledExecutorService publisher;

    public EmbeddedMetricsPublisher(String namespace, String dimensionName, String dimensionValue,
            List<Metric> metrics, long intervalMillis) {
        this.namespace = namespace;
        this.dimensionName = dimensionName;
        this.dimensionValue = sanitize(dimensionValue);
        this.metrics = List.copyOf(metrics);
        this.intervalMillis = intervalMillis;
        this.publisher = Executors.newSingleThreadScheduledExecutor(runnable -> {
            // 監視スレッドとは別に立てる。stdout がブロックしたときに
            // 遅延の観測そのものが止まらないようにするため
            Thread thread = new Thread(runnable, "emf-publisher");
            thread.setDaemon(true);
            return thread;
        });
    }

    public void start() {
        publisher.scheduleAtFixedRate(this::publish, intervalMillis, intervalMillis, TimeUnit.MILLISECONDS);
    }

    @Override
    public void close() {
        publisher.shutdownNow();
    }

    private void publish() {
        try {
            System.out.println(render(System.currentTimeMillis()));
        } catch (RuntimeException e) {
            // 計測の本体ではないので、送信の失敗で条件を落とさない
            System.err.println("EMF の送信に失敗しました: " + e);
        }
    }

    /**
     * EMF の 1 行を組む。
     *
     * <p>Jackson を使わず手で組んでいるのは、common モジュールに JSON の依存を持ち込まないため。
     * 値は数値、次元は英数字に落としてあるのでエスケープの必要がない。
     *
     * <p>{@code StorageResolution} を 1 にしているのは、負荷が 70 秒しかないため。
     * 既定の 60 秒粒度では山が 1 点に潰れて「CPU は平らだが遅延だけ跳ねる」図が描けない。
     */
    String render(long timestampMillis) {
        StringJoiner definitions = new StringJoiner(",");
        StringJoiner values = new StringJoiner(",");
        for (Metric metric : metrics) {
            definitions.add("{\"Name\":\"" + metric.name() + "\",\"Unit\":\"" + metric.unit()
                    + "\",\"StorageResolution\":1}");
            values.add("\"" + metric.name() + "\":" + metric.value().get());
        }
        return "{\"_aws\":{\"Timestamp\":" + timestampMillis
                + ",\"CloudWatchMetrics\":[{\"Namespace\":\"" + namespace
                + "\",\"Dimensions\":[[\"" + dimensionName + "\"]]"
                + ",\"Metrics\":[" + definitions + "]}]}"
                + ",\"" + dimensionName + "\":\"" + dimensionValue + "\""
                + "," + values + "}";
    }

    /** 次元の値は条件名（{@code webflux-blocking} など）なので、英数字とハイフンだけに落とす。 */
    private static String sanitize(String value) {
        if (value == null || value.isBlank()) {
            return "unknown";
        }
        return value.replaceAll("[^A-Za-z0-9._\\-]", "-");
    }
}
