package com.ryotashi29.cnj.runtime.concurrency.common.metrics;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class DatabaseThreadObserverTest {

    @Test
    void ログの1行はmeasureAwsが拾う形になっている() {
        var sample = new DatabaseThreadObserver.Sample(10, 4, 5, 6);

        assertThat(sample.logLine()).isEqualTo("DB_THREADS borrowed=10 sleeping=4 active=5 running=6");
    }

    @Test
    void 接続できないときは観測していないと申告する() {
        // ドライバが無い URL。0 本を返すのではなく、観測していないことを申告させたい
        try (var observer = new DatabaseThreadObserver("jdbc:unknown://nowhere", "u", "p", () -> 0, 500)) {
            assertThat(observer.start()).isFalse();
            assertThat(observer.available()).isFalse();
            assertThat(observer.snapshot())
                    .containsEntry("available", false)
                    .doesNotContainKey("sleeping");
            assertThat((String) observer.snapshot().get("status")).startsWith("利用不可");
        }
    }
}
