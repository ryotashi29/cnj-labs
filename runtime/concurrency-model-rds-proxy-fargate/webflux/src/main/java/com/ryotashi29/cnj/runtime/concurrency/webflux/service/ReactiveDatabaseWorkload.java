package com.ryotashi29.cnj.runtime.concurrency.webflux.service;

import com.ryotashi29.cnj.runtime.concurrency.common.config.WorkloadProperties;
import com.ryotashi29.cnj.runtime.concurrency.common.dto.WorkloadResult;
import com.ryotashi29.cnj.runtime.concurrency.common.web.CapacityExceededException;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import io.r2dbc.spi.ConnectionFactory;
import org.springframework.r2dbc.connection.R2dbcTransactionManager;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.reactive.TransactionalOperator;
import reactor.core.publisher.Mono;

/**
 * R2DBC の接続を一定時間占有するワークロード。mvc 側の {@code DatabaseWorkload} と同じ SQL を投げる。
 *
 * <p>この検証の要点は、<b>理論上限の式がモデルに依存しない</b>ことを示すこと。
 * r2dbc-pool にもプールサイズ（{@code maxSize}）があるため、上限は mvc と同じく
 * {@code プールサイズ / 保持時間} で決まる。ノンブロッキングにしても接続数という上限は消えない。
 */
@Service
public class ReactiveDatabaseWorkload {

    /** usingWhen が許可の取得成功を表すために必要なだけのトークン。値に意味はない。 */
    private static final Object PERMIT = new Object();

    private final DatabaseClient client;
    private final WorkloadProperties properties;
    private final AsyncSemaphore semaphore;
    private final TransactionalOperator transaction;

    /**
     * トランザクションマネージャは自動構成に頼らずここで作る。{@code DatabaseClient} と
     * 同じ {@link ConnectionFactory}（r2dbc-pool）を渡せば、クエリはトランザクションに参加する。
     */
    public ReactiveDatabaseWorkload(DatabaseClient client, ConnectionFactory connectionFactory,
            WorkloadProperties properties) {
        this.client = client;
        this.properties = properties;
        this.semaphore = new AsyncSemaphore(properties.permits());
        this.transaction = TransactionalOperator.create(new R2dbcTransactionManager(connectionFactory));
    }

    /** 制限なしに接続を取りにいく。待ち行列は r2dbc-pool の acquire 待ちに溜まる。 */
    public Mono<WorkloadResult> holdConnection(Integer requestedMillis) {
        int holdMillis = properties.clampHoldMillis(requestedMillis);
        return query(holdMillis, "db", 0L);
    }

    /**
     * トランザクションの中で接続を握ったまま、アプリ側で保持時間だけ待つ。mvc 側の
     * {@code holdConnectionInTransaction} と同じ形。
     *
     * <p>待つのはタイマー（{@code delayElement}）なのでイベントループはブロックしない。
     * それでも接続は握ったままなので、上限は r2dbc-pool の maxSize で決まる。
     * ノンブロッキングにしても接続数という上限は消えないことを、DB 側のスレッドが
     * 先に尽きない条件で示すためのもの。
     */
    public Mono<WorkloadResult> holdConnectionInTransaction(Integer requestedMillis) {
        int holdMillis = properties.clampHoldMillis(requestedMillis);
        return client.sql("SELECT CONNECTION_ID()")
                .map(row -> row.get(0, Long.class))
                .one()
                .delayElement(Duration.ofMillis(holdMillis))
                .map(backendConnectionId -> WorkloadResult.of("tx-hold", holdMillis, 0L, backendConnectionId))
                .as(transaction::transactional);
    }

    /**
     * 同時実行数を明示的に制限したうえで接続を取る。溢れは 503。
     *
     * <p>{@code usingWhen} を使うのは、許可の取得と返却を確実に対にするため。
     * 単に {@code doFinally} を後ろに付けると、許可を取った直後にクライアントが切断した場合に
     * 返却が走らず、許可が 1 つずつ失われていく。
     */
    public Mono<WorkloadResult> holdConnectionBounded(Integer requestedMillis) {
        int holdMillis = properties.clampHoldMillis(requestedMillis);
        Duration acquireTimeout = Duration.ofMillis(properties.acquireTimeoutMillis());

        // 待ち時間は購読ごとに測りたいので defer で包む。組み立て時刻を起点にすると、
        // 高負荷でイベントループが詰まっているときに実際の待ちとずれる
        return Mono.defer(() -> {
            long startedAt = System.nanoTime();
            Mono<Object> permit = semaphore.acquire()
                    .timeout(acquireTimeout)
                    .thenReturn(PERMIT)
                    .onErrorMap(TimeoutException.class,
                            e -> new CapacityExceededException(properties.permits(), elapsedMillis(startedAt)));

            return Mono.usingWhen(
                    permit,
                    ignored -> query(holdMillis, "bounded", elapsedMillis(startedAt)),
                    ignored -> Mono.fromRunnable(semaphore::release));
        });
    }

    public int availablePermits() {
        return semaphore.availablePermits();
    }

    public int permitQueueLength() {
        return semaphore.queueLength();
    }

    /**
     * 保持時間ぶん DB 側で待ち、続けてバックエンド接続 ID を読む。
     *
     * <p>2 クエリに分けているのは mvc 側と同じ理由。トランザクションを張っていないため、
     * RDS Proxy が多重化していれば 2 つのクエリは別のバックエンド接続に載りうる。
     * どちらのスタックも「保持の長いクエリ 1 本 + 即時クエリ 1 本」で揃えている。
     */
    private Mono<WorkloadResult> query(int holdMillis, String mode, long waitedMillis) {
        return client.sql("SELECT SLEEP(:seconds)")
                .bind("seconds", holdMillis / 1000.0)
                .fetch()
                .first()
                .then(client.sql("SELECT CONNECTION_ID()")
                        .map(row -> row.get(0, Long.class))
                        .one())
                // 保持時間 0 のときは SLEEP が即返るため、defaultIfEmpty ではなく map で受ける。
                // CONNECTION_ID() は必ず 1 行返るので one() で足りる
                .map(backendConnectionId ->
                        WorkloadResult.of(mode, holdMillis, waitedMillis, backendConnectionId));
    }

    private static long elapsedMillis(long startedAtNanos) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAtNanos);
    }
}
