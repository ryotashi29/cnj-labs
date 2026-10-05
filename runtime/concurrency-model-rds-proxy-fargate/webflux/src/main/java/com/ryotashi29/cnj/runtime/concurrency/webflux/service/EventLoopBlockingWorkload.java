package com.ryotashi29.cnj.runtime.concurrency.webflux.service;

import com.ryotashi29.cnj.runtime.concurrency.common.config.WorkloadProperties;
import com.ryotashi29.cnj.runtime.concurrency.common.dto.WorkloadResult;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * WebFlux のイベントループ上でブロッキング JDBC を呼ぶワークロード。<b>意図的に間違ったコード。</b>
 *
 * <p>仮説 2 の中心。仮想スレッドのピニングと構造的に同じ失敗を、WebFlux で再現する。
 *
 * <table>
 *   <caption>ブロックされる「数の限られたスレッド」</caption>
 *   <tr><th>モデル</th><th>ブロックされるもの</th><th>本数</th></tr>
 *   <tr><td>Virtual Thread + synchronized</td><td>キャリアスレッド</td>
 *       <td>{@code jdk.virtualThreadScheduler.parallelism}（デフォルト = コア数）</td></tr>
 *   <tr><td>WebFlux + ブロッキング JDBC</td><td>イベントループスレッド</td>
 *       <td>{@code reactor.netty.ioWorkerCount}（デフォルト = max(4, コア数)）</td></tr>
 * </table>
 *
 * <p>どちらも上限がコア数程度で決まるため、コアの多い開発機では症状が出ず、
 * Fargate の小さいタスクサイズで初めて現れる。<b>これが「ローカルでは再現しない」の正体</b>で、
 * Virtual Thread 固有の話ではなく並行モデル共通の構造だと示せることが、この条件を置く理由。
 *
 * <p>{@link #blockEventLoop} が {@code subscribeOn(Schedulers.boundedElastic())} を付けないのは
 * 意図的。付けると正しいコードになってしまい、測りたい失敗が消える。プールサイズは
 * {@code db} モードの r2dbc-pool と同じ {@code POOL_SIZE} に揃えているので、
 * 「接続数は同じなのにスループットだけ落ちる」ことを示せる。
 *
 * <p>その修正版が {@link #blockOnBoundedElastic}。<b>差分は 1 行だけ</b>で、SQL も保持時間も
 * プールも同じ。仮説 2 の対策のうち「隔離」に対応し、仮説 1 の {@code bounded} と同じ位置に立つ。
 */
@Service
public class EventLoopBlockingWorkload {

    private final JdbcTemplate jdbcTemplate;
    private final WorkloadProperties properties;

    public EventLoopBlockingWorkload(JdbcTemplate jdbcTemplate, WorkloadProperties properties) {
        this.jdbcTemplate = jdbcTemplate;
        this.properties = properties;
    }

    public Mono<WorkloadResult> blockEventLoop(Integer requestedMillis) {
        int holdMillis = properties.clampHoldMillis(requestedMillis);
        // fromCallable の中身は購読したスレッド、つまり Netty のイベントループ上で走る。
        // スケジューラを指定していないので、ここで JDBC がブロックする
        return query(holdMillis, "blocking");
    }

    /**
     * {@link #blockEventLoop} の修正版。ブロッキング呼び出しを専用のスレッドプールへ追い出す。
     *
     * <p>{@code boundedElastic} はデフォルトで {@code コア数 × 10} 本まで伸びる、
     * <b>同時実行数のために用意されたスレッド</b>。ここはブロックしてよい。ブロックしてはいけないのは
     * イベントループ（並列実行数のために用意されたスレッド）の側。
     * つまりこの 1 行がやっているのは、<b>ブロックしてよい場所へブロッキングを移すこと</b>。
     *
     * <p><b>ランタイムが自動でやってくれないのがここの肝。</b>仮想スレッドなら
     * ブロッキングを検出してアンマウントできるが（JDK 24 以降は
     * {@code synchronized} でも）、リアクティブのコールバックは単なるスタックフレームなので
     * 移せるのは書き手だけ。{@code subscribeOn} を書き忘れたコードを JDK を上げて救うことはできない。
     */
    public Mono<WorkloadResult> blockOnBoundedElastic(Integer requestedMillis) {
        int holdMillis = properties.clampHoldMillis(requestedMillis);
        return query(holdMillis, "blocking-isolated")
                .subscribeOn(Schedulers.boundedElastic());
    }

    /** 失敗版と修正版で同じ SQL・同じプールを使う。差分を subscribeOn の 1 行だけに閉じ込めるため。 */
    private Mono<WorkloadResult> query(int holdMillis, String mode) {
        return Mono.fromCallable(() -> {
            jdbcTemplate.queryForObject("SELECT SLEEP(?)", Integer.class, holdMillis / 1000.0);
            Long backendConnectionId =
                    jdbcTemplate.queryForObject("SELECT CONNECTION_ID()", Long.class);
            return WorkloadResult.of(mode, holdMillis, 0L, backendConnectionId);
        });
    }
}
