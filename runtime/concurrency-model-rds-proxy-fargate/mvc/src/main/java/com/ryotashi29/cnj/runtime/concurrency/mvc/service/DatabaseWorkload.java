package com.ryotashi29.cnj.runtime.concurrency.mvc.service;

import com.ryotashi29.cnj.runtime.concurrency.common.config.WorkloadProperties;
import com.ryotashi29.cnj.runtime.concurrency.common.dto.WorkloadResult;
import com.ryotashi29.cnj.runtime.concurrency.common.web.CapacityExceededException;
import com.ryotashi29.cnj.runtime.concurrency.mvc.mapper.WorkloadMapper;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * HikariCP の接続を一定時間占有するワークロード。
 *
 * <p>理論上のスループット上限は {@code プールサイズ / 保持時間} で決まり、仮想スレッドの有効・無効では変わらない。
 * この上限を実測が再現することが、仮説 1 の土台になる。
 */
@Service
public class DatabaseWorkload {

    private final WorkloadMapper mapper;
    private final WorkloadProperties properties;
    private final Semaphore permits;
    private final TransactionTemplate transaction;

    public DatabaseWorkload(WorkloadMapper mapper, WorkloadProperties properties,
            PlatformTransactionManager transactionManager) {
        this.mapper = mapper;
        this.properties = properties;
        this.permits = new Semaphore(properties.permits());
        this.transaction = new TransactionTemplate(transactionManager);
    }

    /**
     * 制限なしに接続を取りにいく。仮想スレッドを有効にすると、Tomcat のスレッド数という暗黙の同時実行制限が外れ、
     * 待ち行列が HikariCP の接続待ちに移動する。
     */
    public WorkloadResult holdConnection(Integer requestedMillis) {
        int holdMillis = properties.clampHoldMillis(requestedMillis);
        return new Query(holdMillis).run("db", 0L);
    }

    /**
     * トランザクションの中で接続を握ったまま、<b>アプリ側で</b>保持時間だけ待つ。
     *
     * <p>{@link #holdConnection} の {@code SLEEP()} は接続だけでなく Aurora MySQL の中の処理（DB 側のスレッド）まで占有し、
     * 借りている接続が 25 本でも DB の中で同時に進むのは 3〜4 本で頭打ちになった（2026-09-26）。
     * これでは上限がプールの大きさと無関係になり、{@code プールサイズ / 保持時間} を確かめられない。
     * こちらは待っている間 DB に何も投げないので、占有するのは接続だけになる。
     *
     * <p>現場でよくある「トランザクションの中で外部 API を呼んで、接続を握ったまま待つ」の再現でもある。
     * トランザクションにしているのは RDS Proxy のため。トランザクションの外で待つと、その間に
     * プロキシがバックエンド接続を他へ回してしまい、接続を握ったことにならない。
     * 最初のクエリでトランザクションが始まり、コミットまでバックエンド接続が固定される。
     */
    public WorkloadResult holdConnectionInTransaction(Integer requestedMillis) {
        int holdMillis = properties.clampHoldMillis(requestedMillis);
        return transaction.execute(status -> {
            Long backendConnectionId = mapper.backendConnectionId();
            try {
                // 仮想スレッドならここでキャリアを離す。プラットフォームスレッドなら Tomcat のスレッドがブロックされる。
                // どちらでも接続は握ったままなので、上限は接続の本数で決まる
                Thread.sleep(holdMillis);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("保持中に中断されました", e);
            }
            return WorkloadResult.of("tx-hold", holdMillis, 0L, backendConnectionId);
        });
    }

    /**
     * セマフォで同時 DB アクセス数を明示的に制限する。許可が取れなければ待たずに 503 を返す。
     *
     * <p>接続待ちでタイムアウトさせるのではなく、アプリの入口で溢れを表明するのが狙い。
     */
    public WorkloadResult holdConnectionBounded(Integer requestedMillis) {
        int holdMillis = properties.clampHoldMillis(requestedMillis);
        long startedAt = System.nanoTime();
        boolean acquired;
        try {
            acquired = permits.tryAcquire(properties.acquireTimeoutMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("同時実行の許可待ちが中断されました", e);
        }
        long waitedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt);
        if (!acquired) {
            throw new CapacityExceededException(properties.permits(), waitedMillis);
        }
        try {
            return new Query(holdMillis).run("bounded", waitedMillis);
        } finally {
            permits.release();
        }
    }

    /** 空きスロット数。診断エンドポイントから覗く。 */
    public int availablePermits() {
        return permits.availablePermits();
    }

    /**
     * 保持時間ぶん DB 側で待ち、続けてバックエンド接続 ID を読む。
     *
     * <p>2 つのクエリを 1 リクエストで投げているのは、RDS Proxy が接続を多重化しているかを
     * 同じリクエストの中で確認できるようにするため。トランザクションを張っていないので、
     * 多重化が効いていれば 2 つのクエリは別のバックエンド接続に載りうる。
     */
    private final class Query {
        private final int holdMillis;

        private Query(int holdMillis) {
            this.holdMillis = holdMillis;
        }

        private WorkloadResult run(String mode, long waitedMillis) {
            mapper.sleep(holdMillis / 1000.0);
            Long backendConnectionId = mapper.backendConnectionId();
            return WorkloadResult.of(mode, holdMillis, waitedMillis, backendConnectionId);
        }
    }
}
