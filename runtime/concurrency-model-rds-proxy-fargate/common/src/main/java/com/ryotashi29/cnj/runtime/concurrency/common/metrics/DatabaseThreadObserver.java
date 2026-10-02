package com.ryotashi29.cnj.runtime.concurrency.common.metrics;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.IntSupplier;

/**
 * DB の中で、いま本当に {@code SLEEP()} を実行している接続が何本あるかを数える。
 *
 * <p>アプリ側から見えるのは「接続を何本使用しているか」まで。使用中の接続の上で SQL が
 * <b>DB の中で実際に走り出しているか</b>は、アプリからは見えない。使用中の本数と
 * 走っている本数を同じ瞬間に並べると、両者の差が「DB の中で順番待ちしている本数」になる。
 *
 * <p>数えるのは {@code information_schema.PROCESSLIST} のうち、{@code INFO} が
 * {@code SELECT SLEEP} で始まる行。DB がまだ SQL を読み始めていない接続は {@code INFO} が空なので、
 * ここには数えられない。つまりこの値は「受け付けた本数」ではなく「処理中の本数」になる。
 *
 * <p><b>観測用の接続はアプリの接続プールから借りない。</b>プールが詰まっている最中に観測まで
 * 待たされると、一番知りたい瞬間の値が欠ける。{@link StarvationProbe} と同じく、
 * 観測者は観測対象の資源を共有しない。同じ理由で接続先も RDS Proxy ではなく
 * Aurora の writer に直接つなぐ（{@code measure-aws.sh} が渡す）。
 */
public final class DatabaseThreadObserver implements AutoCloseable {

    /**
     * 自分自身（観測用の接続）は数えない。{@code INFO} が SLEEP でないので sleeping には
     * もともと入らないが、active からも外して「アプリ由来の本数」だけにする。
     */
    private static final String PROCESSLIST_SQL = """
            SELECT COALESCE(SUM(INFO LIKE 'SELECT SLEEP%'), 0) AS sleeping,
                   COALESCE(SUM(COMMAND <> 'Sleep'), 0)        AS active
              FROM information_schema.PROCESSLIST
             WHERE ID <> CONNECTION_ID()
            """;

    private static final String THREADS_RUNNING_SQL = "SHOW GLOBAL STATUS LIKE 'Threads_running'";

    private final String jdbcUrl;
    private final String user;
    private final String password;
    private final IntSupplier borrowedConnections;
    private final long intervalMillis;
    private final ScheduledExecutorService sampler;
    private Connection connection;
    private volatile Sample last;
    private volatile int sleepingHighWater;
    private volatile String status = "未開始";

    /**
     * 1 回ぶんの観測値。同じ瞬間に取った 4 つを 1 組で扱う。
     *
     * @param borrowed アプリが使用中の接続の本数
     * @param sleeping DB の中で SLEEP を実行している本数
     * @param active   DB の中で何か実行している本数（SLEEP 以外も含む）
     * @param running  {@code Threads_running}。観測用の接続自身も 1 本含む
     */
    public record Sample(int borrowed, int sleeping, int active, int running) {

        /** ログの 1 行。{@code measure-aws.sh} がこの形で拾って、borrowed ごとに集計する。 */
        public String logLine() {
            return "DB_THREADS borrowed=" + borrowed + " sleeping=" + sleeping
                    + " active=" + active + " running=" + running;
        }
    }

    /**
     * @param jdbcUrl             観測用の接続先。アプリのプールとは別に 1 本だけ張る
     * @param borrowedConnections アプリが使用中の接続の本数を返す。HikariCP なら activeConnections
     * @param intervalMillis      観測の間隔
     */
    public DatabaseThreadObserver(String jdbcUrl, String user, String password,
            IntSupplier borrowedConnections, long intervalMillis) {
        this.jdbcUrl = jdbcUrl;
        this.user = user;
        this.password = password;
        this.borrowedConnections = borrowedConnections;
        this.intervalMillis = intervalMillis;
        this.sampler = Executors.newSingleThreadScheduledExecutor(runnable -> {
            // 仮想スレッドにはしない。キャリアスレッドがブロックされる条件でも観測を止めないため
            Thread thread = new Thread(runnable, "db-thread-observer");
            thread.setDaemon(true);
            return thread;
        });
    }

    /**
     * 1 回目の観測を同期で行い、成功したときだけ定期観測を始める。
     *
     * <p>{@link PinnedEventRecorder#start()} と同じく、戻り値でメトリクスを登録するかを決めさせる。
     * 接続できないまま 0 を送ると「DB の中で何も走っていない」と読めてしまう。
     *
     * @return 観測を始められたか
     */
    public boolean start() {
        try {
            sample();
        } catch (SQLException e) {
            status = "利用不可: " + e.getMessage();
            closeConnection();
            return false;
        }
        status = "稼働中";
        sampler.scheduleAtFixedRate(this::tick, intervalMillis, intervalMillis, TimeUnit.MILLISECONDS);
        return true;
    }

    /** 観測できているか。{@code false} のときの 0 本は「観測していない」の意味。 */
    public boolean available() {
        return last != null;
    }

    /** 前回呼ばれてから観測した sleeping の最大値を返し、高水位を戻す。EMF の送信側が使う。 */
    public int takeMaxSleeping() {
        int value = sleepingHighWater;
        sleepingHighWater = 0;
        return value;
    }

    /** 診断エンドポイント用。直近の観測値と状態を返す。 */
    public Map<String, Object> snapshot() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("available", available());
        result.put("status", status);
        result.put("jdbcUrl", jdbcUrl);
        Sample sample = last;
        if (sample != null) {
            result.put("borrowed", sample.borrowed());
            result.put("sleeping", sample.sleeping());
            result.put("active", sample.active());
            result.put("threadsRunning", sample.running());
        }
        return result;
    }

    @Override
    public void close() {
        sampler.shutdownNow();
        closeConnection();
    }

    private void tick() {
        try {
            System.out.println(sample().logLine());
            status = "稼働中";
        } catch (SQLException | RuntimeException e) {
            // 観測の失敗で計測を落とさない。次の回で張り直す
            status = "エラー: " + e.getMessage();
            closeConnection();
        }
    }

    private Sample sample() throws SQLException {
        Connection current = connection();
        // borrowed を先に読む。DB への問い合わせ中にアプリ側の本数が動くので、
        // 問い合わせの直前に読んだほうが「同じ瞬間」に近い
        int borrowed = borrowedConnections.getAsInt();
        int sleeping;
        int active;
        int running;
        try (Statement statement = current.createStatement()) {
            try (ResultSet rows = statement.executeQuery(PROCESSLIST_SQL)) {
                rows.next();
                sleeping = rows.getInt("sleeping");
                active = rows.getInt("active");
            }
            try (ResultSet rows = statement.executeQuery(THREADS_RUNNING_SQL)) {
                running = rows.next() ? rows.getInt(2) : -1;
            }
        }
        Sample sample = new Sample(borrowed, sleeping, active, running);
        last = sample;
        sleepingHighWater = Math.max(sleepingHighWater, sleeping);
        return sample;
    }

    private Connection connection() throws SQLException {
        if (connection == null || connection.isClosed()) {
            connection = DriverManager.getConnection(jdbcUrl, user, password);
        }
        return connection;
    }

    private void closeConnection() {
        if (connection == null) {
            return;
        }
        try {
            connection.close();
        } catch (SQLException ignored) {
            // 張り直すために閉じるだけなので、閉じられなくても続ける
        }
        connection = null;
    }
}
