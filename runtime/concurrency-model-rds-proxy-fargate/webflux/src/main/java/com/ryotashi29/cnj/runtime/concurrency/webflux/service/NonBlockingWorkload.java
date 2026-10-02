package com.ryotashi29.cnj.runtime.concurrency.webflux.service;

import com.ryotashi29.cnj.runtime.concurrency.common.config.WorkloadProperties;
import com.ryotashi29.cnj.runtime.concurrency.common.dto.WorkloadResult;
import java.time.Duration;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

/**
 * DB を使わず、同じ時間だけ「待つ」だけのワークロード。対照群。
 *
 * <p>mvc 側の {@code /api/nodb} と対になる。あちらは仮想スレッドをアンマウントさせて待ち、
 * こちらはタイマーで待つ。どちらもスレッドを占有しないため、同時接続数を上げても伸びる。
 * これがないと「WebFlux は速くなかった」という誤った結論になりうる。
 * 伸びない原因が並行モデルではなく後段の接続数であることを示すために必要な比較対象。
 */
@Service
public class NonBlockingWorkload {

    private final WorkloadProperties properties;

    public NonBlockingWorkload(WorkloadProperties properties) {
        this.properties = properties;
    }

    public Mono<WorkloadResult> delay(Integer requestedMillis) {
        int holdMillis = properties.clampHoldMillis(requestedMillis);
        return Mono.delay(Duration.ofMillis(holdMillis))
                // WorkloadResult.of は現在のスレッドを記録する。ここは parallel スケジューラの
                // スレッドになり、db モードでは r2dbc のスレッドになる。どのスレッドで
                // 完了したかが分かると、ブロックしていないことをレスポンスだけで確認できる
                .map(ignored -> WorkloadResult.of("nodb", holdMillis, 0L, null));
    }
}
