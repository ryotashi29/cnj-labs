package com.ryotashi29.cnj.runtime.concurrency.mvc.service;

import com.ryotashi29.cnj.runtime.concurrency.common.config.WorkloadProperties;
import com.ryotashi29.cnj.runtime.concurrency.common.dto.WorkloadResult;
import java.util.concurrent.TimeUnit;
import org.springframework.stereotype.Service;

/**
 * DB を使わず、同じ時間だけブロックするだけのワークロード。対照群。
 *
 * <p>これがないと「仮想スレッドは効かなかった」という誤った結論になりうる。同じ保持時間で
 * DB ありは上限に張り付き、DB なしは伸びることを並べて示すために必要な比較対象。
 * 仮想スレッドが効かないのではなく、後段の接続数が上限を決めていることの証明になる。
 */
@Service
public class NoDatabaseWorkload {

    private final WorkloadProperties properties;

    public NoDatabaseWorkload(WorkloadProperties properties) {
        this.properties = properties;
    }

    public WorkloadResult block(Integer requestedMillis) {
        int holdMillis = properties.clampHoldMillis(requestedMillis);
        sleep(holdMillis);
        return WorkloadResult.of("nodb", holdMillis, 0L, null);
    }

    static void sleep(int millis) {
        try {
            TimeUnit.MILLISECONDS.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("待機が中断されました", e);
        }
    }
}
