package com.ryotashi29.cnj.runtime.concurrency.webflux.controller;

import com.ryotashi29.cnj.runtime.concurrency.common.dto.WorkloadResult;
import com.ryotashi29.cnj.runtime.concurrency.webflux.service.EventLoopBlockingWorkload;
import com.ryotashi29.cnj.runtime.concurrency.webflux.service.NonBlockingWorkload;
import com.ryotashi29.cnj.runtime.concurrency.webflux.service.ReactiveDatabaseWorkload;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

/**
 * mvc 側と同じパス・同じクエリパラメータ・同じレスポンス形。k6 のシナリオを共有するための前提。
 *
 * <p>{@code /api/pinned} の代わりに {@code /api/blocking} がある。仮想スレッドのピニングに
 * 対応するのはイベントループのブロックであり、WebFlux にキャリアスレッドは存在しないため。
 */
@RestController
@RequestMapping("/api")
public class WorkloadController {

    private final ReactiveDatabaseWorkload databaseWorkload;
    private final NonBlockingWorkload nonBlockingWorkload;
    private final EventLoopBlockingWorkload eventLoopBlockingWorkload;

    public WorkloadController(ReactiveDatabaseWorkload databaseWorkload,
            NonBlockingWorkload nonBlockingWorkload,
            EventLoopBlockingWorkload eventLoopBlockingWorkload) {
        this.databaseWorkload = databaseWorkload;
        this.nonBlockingWorkload = nonBlockingWorkload;
        this.eventLoopBlockingWorkload = eventLoopBlockingWorkload;
    }

    /** R2DBC で接続を保持する。スループット上限は r2dbc-pool の maxSize で決まる。 */
    @GetMapping("/db")
    public Mono<WorkloadResult> db(@RequestParam(required = false) Integer ms) {
        return databaseWorkload.holdConnection(ms);
    }

    /** トランザクションの中で接続を握ったまま、タイマーで待つ。DB 側のスレッドは使わない。 */
    @GetMapping("/tx-hold")
    public Mono<WorkloadResult> txHold(@RequestParam(required = false) Integer ms) {
        return databaseWorkload.holdConnectionInTransaction(ms);
    }

    /** 同時実行数を明示的に制限したうえで保持する。溢れは 503。 */
    @GetMapping("/bounded")
    public Mono<WorkloadResult> bounded(@RequestParam(required = false) Integer ms) {
        return databaseWorkload.holdConnectionBounded(ms);
    }

    /** DB を使わずタイマーで同じ時間待つ。対照群。 */
    @GetMapping("/nodb")
    public Mono<WorkloadResult> nodb(@RequestParam(required = false) Integer ms) {
        return nonBlockingWorkload.delay(ms);
    }

    /** イベントループ上でブロッキング JDBC を呼ぶ。意図的に間違ったコード。 */
    @GetMapping("/blocking")
    public Mono<WorkloadResult> blocking(@RequestParam(required = false) Integer ms) {
        return eventLoopBlockingWorkload.blockEventLoop(ms);
    }

    /** {@code /api/blocking} の修正版。ブロッキングを boundedElastic に隔離する。差分は 1 行。 */
    @GetMapping("/blocking-isolated")
    public Mono<WorkloadResult> blockingIsolated(@RequestParam(required = false) Integer ms) {
        return eventLoopBlockingWorkload.blockOnBoundedElastic(ms);
    }
}
