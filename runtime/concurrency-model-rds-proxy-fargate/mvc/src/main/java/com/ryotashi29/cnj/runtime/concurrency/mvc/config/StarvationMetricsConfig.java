package com.ryotashi29.cnj.runtime.concurrency.mvc.config;

import com.ryotashi29.cnj.runtime.concurrency.common.config.StarvationProperties;
import com.ryotashi29.cnj.runtime.concurrency.common.metrics.DatabaseThreadObserver;
import com.ryotashi29.cnj.runtime.concurrency.common.metrics.EmbeddedMetricsPublisher;
import com.ryotashi29.cnj.runtime.concurrency.common.metrics.PinnedEventRecorder;
import com.ryotashi29.cnj.runtime.concurrency.common.metrics.StarvationProbe;
import com.ryotashi29.cnj.runtime.concurrency.mvc.service.DatabaseWorkload;
import com.zaxxer.hikari.HikariDataSource;
import com.zaxxer.hikari.HikariPoolMXBean;
import java.lang.management.ManagementFactory;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;
import javax.sql.DataSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * mvc 側で監視するのは<b>キャリアスレッド</b>。仮想スレッドを 1 本起動して、
 * 走り出すまでの時間を測る。
 *
 * <p>キャリアスレッドが全部ブロックされていれば新しい仮想スレッドはマウントされず、この時間が伸びる。
 * {@code /api/pinned} を JDK 21 で叩いたときに伸び、JDK 25（JEP 491）や
 * {@code /api/pinned-lock} では伸びない、というのが確かめたいこと。
 *
 * <p><b>{@code VIRTUAL_THREAD_ENABLED=false}（mvc-platform）ではこの値は動かない。</b>
 * それは計測の失敗ではなく正しい結果で、mvc-platform の上限は
 * <b>並列実行数のために用意されたスレッド（キャリア）ではなく、同時実行数のために
 * 用意されたスレッド（Tomcat の 200 本）</b>だから。後者は枯渇しても遅延にならず単に待つだけなので、
 * 既存の {@code tomcat.threads.busy}（actuator）で足りる。
 * <b>作り込みが必要なのは並列実行数の側だけ</b>という非対称がここに出る。
 */
@Configuration
@ConditionalOnProperty(prefix = "starvation", name = "enabled", matchIfMissing = true)
public class StarvationMetricsConfig {

    /** 仮想スレッドスケジューラそのものを監視対象にする。 */
    @Bean
    public StarvationProbe carrierProbe(StarvationProperties properties) {
        Executor virtualThreadScheduler = runnable -> Thread.ofVirtual()
                .name("starvation-probe-vt")
                .start(runnable);
        StarvationProbe probe = new StarvationProbe("carrier",
                Map.of("virtual-thread-scheduler", virtualThreadScheduler),
                properties.probeIntervalMillis());
        probe.start();
        return probe;
    }

    /**
     * ピニングそのものを数える。{@link StarvationProbe} が測るスレッドの空き待ち時間の<b>原因側</b>。
     *
     * <p>空き待ち時間だけでは「詰まった」ことしか言えない。ここを足すと、詰まりの原因が
     * キャリアスレッドを離せないことだったと<b>推測ではなく数値で</b>言える。
     * 購読に失敗しても計測は続けたいので、{@code start()} の戻り値は
     * 送信側で見て、駄目なときはメトリクスを出さない（0 件と混同させない）。
     */
    @Bean
    public PinnedEventRecorder pinnedEventRecorder(StarvationProperties properties) {
        PinnedEventRecorder recorder = new PinnedEventRecorder(properties.pinnedThresholdMillis());
        recorder.start();
        return recorder;
    }

    /**
     * DB の中で SLEEP を実行している本数を数える。{@code DB_OBSERVER_HOST} を渡したときだけ動く。
     *
     * <p>プールを増やしても Proxy を外しても上限が 約 20 rps で動かなかった。
     * アプリが使用中の本数と、DB の中で実際に走っている本数を並べて、
     * <b>接続は足りているのに DB の中で順番待ちしている</b>のかを確かめる。
     * 使用中の本数は HikariCP の activeConnections。プールが起動する前は 0 を返す。
     */
    @Bean(destroyMethod = "close")
    @ConditionalOnExpression("!'${db-observer.host:}'.isEmpty()")
    public DatabaseThreadObserver databaseThreadObserver(DataSource dataSource,
            @Value("${db-observer.url}") String url,
            @Value("${spring.datasource.username}") String user,
            @Value("${spring.datasource.password}") String password,
            @Value("${db-observer.interval-millis:500}") long intervalMillis) throws SQLException {
        HikariDataSource hikari = dataSource.unwrap(HikariDataSource.class);
        DatabaseThreadObserver observer = new DatabaseThreadObserver(url, user, password, () -> {
            HikariPoolMXBean pool = hikari.getHikariPoolMXBean();
            return pool == null ? 0 : pool.getActiveConnections();
        }, intervalMillis);
        observer.start();
        return observer;
    }

    /**
     * {@code CpuUtilized} と同じグラフに重ねるための送信。
     *
     * <p>{@code BoundedPermitsAvailable} を一緒に出しているのは、仮説 1 の結論（上限を
     * 自分で宣言する）が<b>そのまま仮説 2 の観測手段になる</b>ことを示すため。
     * 宣言していない上限は観測できない。
     *
     * <p>ピニングの 2 つは<b>購読できたときだけ登録する</b>。JFR が使えない実行環境で
     * 0 を送ると、「ピニングが起きていない」と読めてしまう。出さなければ CloudWatch 側に
     * 系列が現れないので、要約スクリプトは {@code n/a} として扱える。
     */
    @Bean
    public EmbeddedMetricsPublisher starvationMetricsPublisher(StarvationProperties properties,
            StarvationProbe carrierProbe, PinnedEventRecorder pinnedEventRecorder,
            DatabaseWorkload databaseWorkload, ObjectProvider<DatabaseThreadObserver> databaseThreadObserver) {
        List<EmbeddedMetricsPublisher.Metric> metrics = new ArrayList<>(List.of(
                new EmbeddedMetricsPublisher.Metric(
                        "CarrierLagMillis", "Milliseconds", carrierProbe::takeMaxLagMillis),
                new EmbeddedMetricsPublisher.Metric(
                        "BoundedPermitsAvailable", "Count", databaseWorkload::availablePermits),
                new EmbeddedMetricsPublisher.Metric("LiveThreads", "Count",
                        () -> ManagementFactory.getThreadMXBean().getThreadCount())));
        if (pinnedEventRecorder.available()) {
            metrics.add(new EmbeddedMetricsPublisher.Metric(
                    "PinnedEvents", "Count", pinnedEventRecorder::takeEventCount));
            metrics.add(new EmbeddedMetricsPublisher.Metric(
                    "PinnedMaxMillis", "Milliseconds", pinnedEventRecorder::takeMaxDurationMillis));
        }
        // ピニングと同じ理由で、観測できたときだけ登録する。0 本は「DB で何も走っていない」と読めてしまう
        databaseThreadObserver.ifAvailable(observer -> {
            if (observer.available()) {
                metrics.add(new EmbeddedMetricsPublisher.Metric(
                        "DbSleepingThreads", "Count", observer::takeMaxSleeping));
            }
        });
        EmbeddedMetricsPublisher publisher = new EmbeddedMetricsPublisher(
                properties.namespace(), "Condition", properties.condition(), metrics,
                properties.publishIntervalMillis());
        publisher.start();
        return publisher;
    }
}
