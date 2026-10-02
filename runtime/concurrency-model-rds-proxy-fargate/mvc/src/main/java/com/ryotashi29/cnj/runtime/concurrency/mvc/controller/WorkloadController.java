package com.ryotashi29.cnj.runtime.concurrency.mvc.controller;

import com.ryotashi29.cnj.runtime.concurrency.common.dto.WorkloadResult;
import com.ryotashi29.cnj.runtime.concurrency.mvc.service.DatabaseWorkload;
import com.ryotashi29.cnj.runtime.concurrency.mvc.service.NoDatabaseWorkload;
import com.ryotashi29.cnj.runtime.concurrency.mvc.service.PinnedWorkload;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 4 つのワークロードを同じ形の API で並べる。{@code ms} だけを揃えて比較するのが計測の前提。
 */
@RestController
@RequestMapping("/api")
public class WorkloadController {

    private final DatabaseWorkload databaseWorkload;
    private final NoDatabaseWorkload noDatabaseWorkload;
    private final PinnedWorkload pinnedWorkload;

    public WorkloadController(DatabaseWorkload databaseWorkload,
            NoDatabaseWorkload noDatabaseWorkload,
            PinnedWorkload pinnedWorkload) {
        this.databaseWorkload = databaseWorkload;
        this.noDatabaseWorkload = noDatabaseWorkload;
        this.pinnedWorkload = pinnedWorkload;
    }

    /** DB 接続を保持する。スループット上限はプールサイズで決まる。 */
    @GetMapping("/db")
    public WorkloadResult db(@RequestParam(required = false) Integer ms) {
        return databaseWorkload.holdConnection(ms);
    }

    /** DB 接続をセマフォで制限したうえで保持する。溢れは 503。 */
    /** トランザクションの中で接続を握ったまま、アプリ側で待つ。DB 側のスレッドは使わない。 */
    @GetMapping("/tx-hold")
    public WorkloadResult txHold(@RequestParam(required = false) Integer ms) {
        return databaseWorkload.holdConnectionInTransaction(ms);
    }

    @GetMapping("/bounded")
    public WorkloadResult bounded(@RequestParam(required = false) Integer ms) {
        return databaseWorkload.holdConnectionBounded(ms);
    }

    /** DB を使わずに同じ時間ブロックする。対照群。 */
    @GetMapping("/nodb")
    public WorkloadResult nodb(@RequestParam(required = false) Integer ms) {
        return noDatabaseWorkload.block(ms);
    }

    /** モニタを保持したままブロックする。Java 21 ではキャリアスレッドがピニングされる。 */
    @GetMapping("/pinned")
    public WorkloadResult pinned(@RequestParam(required = false) Integer ms) {
        return pinnedWorkload.blockWhilePinned(ms);
    }

    /** {@code /api/pinned} の修正版。ReentrantLock なので JDK 21 でもキャリアスレッドを解放する。 */
    @GetMapping("/pinned-lock")
    public WorkloadResult pinnedLock(@RequestParam(required = false) Integer ms) {
        return pinnedWorkload.blockWhileLocked(ms);
    }
}
