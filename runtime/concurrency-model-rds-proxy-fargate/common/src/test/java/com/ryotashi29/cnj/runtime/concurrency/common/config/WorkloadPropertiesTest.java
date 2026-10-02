package com.ryotashi29.cnj.runtime.concurrency.common.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class WorkloadPropertiesTest {

    private final WorkloadProperties properties = new WorkloadProperties(200, 5000, 64, 10, 0);

    @Test
    void 省略時は既定の保持時間を使う() {
        assertThat(properties.clampHoldMillis(null)).isEqualTo(200);
    }

    @Test
    void 指定された保持時間をそのまま使う() {
        assertThat(properties.clampHoldMillis(350)).isEqualTo(350);
    }

    @Test
    void 上限を超える保持時間は上限に丸める() {
        // 負荷試験の指定ミスで DB 接続を何十秒も占有させないための歯止め
        assertThat(properties.clampHoldMillis(60_000)).isEqualTo(5000);
    }

    @Test
    void 負の保持時間は0に丸める() {
        // Thread.sleep も SLEEP() も負値を受け付けないため
        assertThat(properties.clampHoldMillis(-1)).isZero();
    }
}
