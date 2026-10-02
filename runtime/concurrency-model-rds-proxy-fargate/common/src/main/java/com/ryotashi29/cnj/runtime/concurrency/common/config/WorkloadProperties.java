package com.ryotashi29.cnj.runtime.concurrency.common.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * ワークロードの調整値。
 *
 * @param defaultHoldMillis {@code ms} パラメータ省略時の保持時間
 * @param maxHoldMillis     受け付ける保持時間の上限。負荷試験の事故で DB 接続を長時間占有しないための歯止め
 * @param lockStripes       {@code /api/pinned} が使うモニタの本数。ロック競合とピニングを切り分けるために 1 本ではなく複数用意する
 * @param permits           {@code /api/bounded} が許可する同時 DB アクセス数
 * @param acquireTimeoutMillis {@code /api/bounded} が許可を待つ上限。超えたら 503 を返して即座に諦める
 */
@ConfigurationProperties(prefix = "workload")
public record WorkloadProperties(
        int defaultHoldMillis,
        int maxHoldMillis,
        int lockStripes,
        int permits,
        long acquireTimeoutMillis) {

    public int clampHoldMillis(Integer requested) {
        int value = requested == null ? defaultHoldMillis : requested;
        return Math.clamp(value, 0, maxHoldMillis);
    }
}
