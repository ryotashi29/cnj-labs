package com.ryotashi29.cnj.runtime.concurrency.webflux.controller;

import com.ryotashi29.cnj.runtime.concurrency.common.diagnostics.JdbcUrls;
import com.ryotashi29.cnj.runtime.concurrency.common.diagnostics.JvmDiagnostics;
import com.ryotashi29.cnj.runtime.concurrency.common.metrics.StarvationProbe;
import com.ryotashi29.cnj.runtime.concurrency.webflux.service.ReactiveDatabaseWorkload;
import com.zaxxer.hikari.HikariDataSource;
import io.r2dbc.pool.ConnectionPool;
import io.r2dbc.spi.ConnectionFactory;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.Map;
import javax.sql.DataSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 計測条件を実行中のプロセス自身に申告させるエンドポイント。mvc 側と対になる。
 *
 * <p>仮説 2 は「少数のスレッドの本数が上限を決める」であり、WebFlux でのその本数は
 * イベントループスレッド数。{@code reactor.netty.ioWorkerCount} を明示していなければ
 * {@code max(4, availableProcessors())} が既定になるため、設定値と実際に立っている本数の
 * 両方を返す。Fargate の 0.25 vCPU で本当に本数が絞られるのかを、推測ではなく実測で押さえる。
 *
 * <p>プールは 2 つ申告する。{@code db} / {@code bounded} が使う r2dbc-pool と、
 * {@code blocking} が使う HikariCP。<b>この 2 つのサイズが一致していることが公平な比較の条件</b>で、
 * 一致していなければ「Tomcat と Netty のオーバーヘッド差」ではなく設定ミスを測ってしまう。
 */
@RestController
public class DiagnosticsController {

    /** Reactor Netty の HTTP サーバ側イベントループのスレッド名接頭辞。 */
    private static final String EVENT_LOOP_THREAD_PREFIX = "reactor-http";

    private final ConnectionFactory connectionFactory;
    private final DataSource dataSource;
    private final ReactiveDatabaseWorkload databaseWorkload;
    private final ObjectProvider<StarvationProbe> starvationProbe;
    private final String r2dbcUrl;

    public DiagnosticsController(ConnectionFactory connectionFactory, DataSource dataSource,
            ReactiveDatabaseWorkload databaseWorkload,
            ObjectProvider<StarvationProbe> starvationProbe,
            @Value("${spring.r2dbc.url}") String r2dbcUrl) {
        this.connectionFactory = connectionFactory;
        this.dataSource = dataSource;
        this.databaseWorkload = databaseWorkload;
        this.starvationProbe = starvationProbe;
        this.r2dbcUrl = r2dbcUrl;
    }

    @GetMapping("/api/diagnostics")
    public Map<String, Object> diagnostics() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("javaVersion", Runtime.version().toString());
        result.put("webStack", "webflux");
        result.put("eventLoop", eventLoop());
        result.put("r2dbcPool", r2dbcPool());
        result.put("connectionPool", hikariPool());
        result.put("boundedPermitsAvailable", databaseWorkload.availablePermits());
        result.put("boundedQueueLength", databaseWorkload.permitQueueLength());
        // イベントループがブロックされていれば lagMillis が伸びる。CPU では見えない枯渇の申告
        starvationProbe.ifAvailable(probe -> result.put("starvation", probe.snapshot()));
        result.put("memory", JvmDiagnostics.memory());
        return result;
    }

    private Map<String, Object> eventLoop() {
        Map<String, Object> eventLoop = new LinkedHashMap<>();
        eventLoop.put("availableProcessors", Runtime.getRuntime().availableProcessors());
        eventLoop.put("ioWorkerCountProperty", System.getProperty("reactor.netty.ioWorkerCount"));
        eventLoop.put("liveEventLoopThreads", JvmDiagnostics.countThreads(EVENT_LOOP_THREAD_PREFIX));
        return eventLoop;
    }

    private Map<String, Object> r2dbcPool() {
        Map<String, Object> pool = new LinkedHashMap<>();
        // 接続先（RDS Proxy 経由か Aurora 直結か）をこの経路についても確定させる。
        // serverPrepStmts が常に false なのは、SERVER_PREP_STMTS を r2dbc 側に繋いでいないため。
        // 繋いでいないことを申告しないと、webflux-r2dbc でピニングが起きないのを
        // 「Reactor だから起きない」と読み違える
        pool.put("url", r2dbcUrl);
        pool.put("serverPrepStmts", false);
        if (!(connectionFactory instanceof ConnectionPool connectionPool)) {
            // spring.r2dbc.pool.enabled=false だとプールなしになり、上限の話が成立しなくなる。
            // 黙って通すと「プールサイズを変えても結果が変わらない」という誤読につながるため明示する
            pool.put("error", "ConnectionFactory がプールではありません: " + connectionFactory.getClass().getName());
            return pool;
        }
        connectionPool.getMetrics().ifPresentOrElse(metrics -> {
            pool.put("maxAllocatedSize", metrics.getMaxAllocatedSize());
            pool.put("acquiredSize", metrics.acquiredSize());
            pool.put("idleSize", metrics.idleSize());
            pool.put("pendingAcquireSize", metrics.pendingAcquireSize());
        }, () -> pool.put("error", "PoolMetrics を取得できませんでした"));
        return pool;
    }

    private Map<String, Object> hikariPool() {
        Map<String, Object> pool = new LinkedHashMap<>();
        // unwrap で得た HikariDataSource は元の Bean そのもの。Closeable だが閉じてはいけない
        try {
            HikariDataSource hikari = dataSource.unwrap(HikariDataSource.class);
            // mvc 側と同じものを同じ名前で申告する。blocking 条件だけがこの経路を使うので、
            // ピニングの計測でここと r2dbc 側のどちらを測ったのかを結果から特定できる
            pool.put("jdbcUrl", hikari.getJdbcUrl());
            pool.put("serverPrepStmts", JdbcUrls.hasServerPrepStmts(hikari.getJdbcUrl()));
            pool.put("maximumPoolSize", hikari.getMaximumPoolSize());
            pool.put("connectionTimeoutMillis", hikari.getConnectionTimeout());
            var mxBean = hikari.getHikariPoolMXBean();
            if (mxBean != null) {
                pool.put("activeConnections", mxBean.getActiveConnections());
                pool.put("idleConnections", mxBean.getIdleConnections());
                pool.put("threadsAwaitingConnection", mxBean.getThreadsAwaitingConnection());
            }
        } catch (SQLException e) {
            pool.put("error", "HikariDataSource を取得できませんでした: " + e.getMessage());
        }
        return pool;
    }
}
