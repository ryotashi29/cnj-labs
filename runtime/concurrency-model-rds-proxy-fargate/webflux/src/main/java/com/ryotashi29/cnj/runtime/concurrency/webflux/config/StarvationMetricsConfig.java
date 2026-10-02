package com.ryotashi29.cnj.runtime.concurrency.webflux.config;

import com.ryotashi29.cnj.runtime.concurrency.common.config.StarvationProperties;
import com.ryotashi29.cnj.runtime.concurrency.common.metrics.EmbeddedMetricsPublisher;
import com.ryotashi29.cnj.runtime.concurrency.common.metrics.StarvationProbe;
import com.ryotashi29.cnj.runtime.concurrency.webflux.service.ReactiveDatabaseWorkload;
import io.netty.channel.EventLoopGroup;
import io.netty.util.concurrent.EventExecutor;
import java.lang.management.ManagementFactory;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.ReactorResourceFactory;
import reactor.netty.http.HttpResources;
import reactor.netty.resources.LoopResources;

/**
 * webflux 側で監視するのは<b>イベントループスレッド</b>。サーバが使っているループ群の
 * 1 本 1 本に何もしないタスクを投げ、走り出すまでの時間の最大値を取る。
 *
 * <p>ループごとに測って最大値を取るのは、{@code reactor.netty.ioWorkerCount} の既定が
 * {@code max(4, コア数)} で、cpu256 でも 4 本あるため。1 本だけ見ると、
 * たまたまブロックされていないループを引いて枯渇を見逃す。
 *
 * <p><b>ここが監視できているかどうかは推測ではなく実測で確かめる。</b>
 * {@code /api/diagnostics} の {@code starvation.targets[].runnerThread} が
 * {@code reactor-http-nio-*} になっていることが、この設定の成立条件。
 */
@Configuration
@ConditionalOnProperty(prefix = "starvation", name = "enabled", matchIfMissing = true)
public class StarvationMetricsConfig {

    /**
     * サーバのイベントループ群を監視対象にする。
     *
     * <p>{@link ReactorResourceFactory} があればそこから辿る。Spring Boot は既定で
     * グローバル資源（{@link HttpResources}）を使うため、Bean が無い構成でも
     * そちらを見れば同じループ群に行き当たる。どちらを引いたかは runnerThread で確認できる。
     */
    @Bean
    public StarvationProbe eventLoopProbe(StarvationProperties properties,
            ObjectProvider<ReactorResourceFactory> resourceFactory) {
        LoopResources loopResources = resourceFactory.stream()
                .map(ReactorResourceFactory::getLoopResources)
                .filter(resources -> resources != null)
                .findFirst()
                .orElseGet(HttpResources::get);
        EventLoopGroup group = loopResources.onServer(LoopResources.DEFAULT_NATIVE);
        Map<String, Executor> targets = new LinkedHashMap<>();
        int index = 0;
        for (EventExecutor executor : group) {
            targets.put("event-loop-" + index++, executor);
        }
        StarvationProbe probe = new StarvationProbe("event-loop", targets,
                properties.probeIntervalMillis());
        probe.start();
        return probe;
    }

    /** mvc 側と同じ名前空間・同じ次元で出す。名前だけメトリクスごとに変える。 */
    @Bean
    public EmbeddedMetricsPublisher starvationMetricsPublisher(StarvationProperties properties,
            StarvationProbe eventLoopProbe, ReactiveDatabaseWorkload databaseWorkload) {
        EmbeddedMetricsPublisher publisher = new EmbeddedMetricsPublisher(
                properties.namespace(), "Condition", properties.condition(),
                List.of(new EmbeddedMetricsPublisher.Metric(
                                "EventLoopLagMillis", "Milliseconds", eventLoopProbe::takeMaxLagMillis),
                        new EmbeddedMetricsPublisher.Metric(
                                "BoundedPermitsAvailable", "Count", databaseWorkload::availablePermits),
                        new EmbeddedMetricsPublisher.Metric(
                                "BoundedQueueLength", "Count", databaseWorkload::permitQueueLength),
                        new EmbeddedMetricsPublisher.Metric("LiveThreads", "Count",
                                () -> ManagementFactory.getThreadMXBean().getThreadCount())),
                properties.publishIntervalMillis());
        publisher.start();
        return publisher;
    }
}
