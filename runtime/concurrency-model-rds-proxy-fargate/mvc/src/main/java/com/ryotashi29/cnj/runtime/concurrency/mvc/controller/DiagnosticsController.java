package com.ryotashi29.cnj.runtime.concurrency.mvc.controller;

import com.ryotashi29.cnj.runtime.concurrency.common.diagnostics.JdbcUrls;
import com.ryotashi29.cnj.runtime.concurrency.common.diagnostics.JvmDiagnostics;
import com.ryotashi29.cnj.runtime.concurrency.common.metrics.DatabaseThreadObserver;
import com.ryotashi29.cnj.runtime.concurrency.common.metrics.PinnedEventRecorder;
import com.ryotashi29.cnj.runtime.concurrency.common.metrics.StarvationProbe;
import com.ryotashi29.cnj.runtime.concurrency.mvc.service.DatabaseWorkload;
import com.zaxxer.hikari.HikariDataSource;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.Map;
import javax.sql.DataSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 計測条件を実行中のプロセス自身に申告させるエンドポイント。
 *
 * <p>仮説 2 の前提（Fargate のタスクサイズが仮想スレッドスケジューラの並列度を決める）は、
 * {@code availableProcessors()} が実際に何を返すかに依存する。これを外から観測できないと
 * 「0.25 vCPU では並列度 1」という前提そのものが検証できないため、計測とセットで用意している。
 */
@RestController
public class DiagnosticsController {

    private final DataSource dataSource;
    private final DatabaseWorkload databaseWorkload;
    private final ObjectProvider<StarvationProbe> starvationProbe;
    private final ObjectProvider<PinnedEventRecorder> pinnedEventRecorder;
    private final ObjectProvider<DatabaseThreadObserver> databaseThreadObserver;
    private final boolean virtualThreadEnabled;

    public DiagnosticsController(DataSource dataSource, DatabaseWorkload databaseWorkload,
            ObjectProvider<StarvationProbe> starvationProbe,
            ObjectProvider<PinnedEventRecorder> pinnedEventRecorder,
            ObjectProvider<DatabaseThreadObserver> databaseThreadObserver,
            @Value("${spring.threads.virtual.enabled:false}") boolean virtualThreadEnabled) {
        this.dataSource = dataSource;
        this.databaseWorkload = databaseWorkload;
        this.starvationProbe = starvationProbe;
        this.pinnedEventRecorder = pinnedEventRecorder;
        this.databaseThreadObserver = databaseThreadObserver;
        this.virtualThreadEnabled = virtualThreadEnabled;
    }

    @GetMapping("/api/diagnostics")
    public Map<String, Object> diagnostics() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("javaVersion", Runtime.version().toString());
        result.put("webStack", "mvc");
        result.put("virtualThreadEnabled", virtualThreadEnabled);
        result.put("scheduler", scheduler());
        result.put("connectionPool", connectionPool());
        result.put("boundedPermitsAvailable", databaseWorkload.availablePermits());
        // キャリアスレッドがブロックされていれば lagMillis が伸びる。CPU では見えない枯渇の申告
        starvationProbe.ifAvailable(probe -> result.put("starvation", probe.snapshot()));
        // スレッドの空き待ち時間が延びた原因側。available が false のときの 0 件は「観測していない」の意味なので、
        // 件数だけでなく購読の状態も申告させる
        pinnedEventRecorder.ifAvailable(recorder -> result.put("pinning", recorder.snapshot()));
        // 使用中の接続の本数と、DB の中で SLEEP を実行している本数。差が DB の中の順番待ち
        databaseThreadObserver.ifAvailable(observer -> result.put("databaseThreads", observer.snapshot()));
        result.put("memory", JvmDiagnostics.memory());
        return result;
    }

    /**
     * 仮想スレッドスケジューラの並列度。システムプロパティで明示されていなければ
     * {@code availableProcessors()} が既定値になるため、両方を並べて返す。
     */
    private Map<String, Object> scheduler() {
        Map<String, Object> scheduler = new LinkedHashMap<>();
        scheduler.put("availableProcessors", Runtime.getRuntime().availableProcessors());
        scheduler.put("parallelismProperty", System.getProperty("jdk.virtualThreadScheduler.parallelism"));
        scheduler.put("maxPoolSizeProperty", System.getProperty("jdk.virtualThreadScheduler.maxPoolSize"));
        return scheduler;
    }

    private Map<String, Object> connectionPool() {
        Map<String, Object> pool = new LinkedHashMap<>();
        // unwrap で得た HikariDataSource は元の Bean そのもの。Closeable だが閉じてはいけない
        try {
            HikariDataSource hikari = dataSource.unwrap(HikariDataSource.class);
            // 実効の URL をそのまま返す。DB_URL を丸ごと差し替えると SERVER_PREP_STMTS は
            // 効かなくなるため、スイッチの値を申告しても取り違えに気づけない。
            // 接続先（RDS Proxy 経由か Aurora 直結か）も同時にここで確定する
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
