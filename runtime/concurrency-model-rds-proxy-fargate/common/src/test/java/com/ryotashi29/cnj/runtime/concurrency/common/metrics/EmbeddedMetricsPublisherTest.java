package com.ryotashi29.cnj.runtime.concurrency.common.metrics;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * EMF の形を固定する。
 *
 * <p>形が崩れても例外は出ず、CloudWatch 側で<b>静かにメトリクスにならないだけ</b>なので、
 * 計測当日に「グラフが空」で気づくことになる。文字列で固定しておく価値がある。
 */
class EmbeddedMetricsPublisherTest {

    @Test
    void CloudWatch_が読める形で組み立てる() {
        try (EmbeddedMetricsPublisher publisher = new EmbeddedMetricsPublisher(
                "cnj/ConcurrencyModel", "Condition", "webflux-blocking",
                List.of(new EmbeddedMetricsPublisher.Metric(
                        "EventLoopLagMillis", "Milliseconds", () -> 1234L)),
                5000)) {
            assertThat(publisher.render(1700000000000L)).isEqualTo(
                    "{\"_aws\":{\"Timestamp\":1700000000000,\"CloudWatchMetrics\":["
                            + "{\"Namespace\":\"cnj/ConcurrencyModel\","
                            + "\"Dimensions\":[[\"Condition\"]],"
                            + "\"Metrics\":[{\"Name\":\"EventLoopLagMillis\","
                            + "\"Unit\":\"Milliseconds\",\"StorageResolution\":1}]}]},"
                            + "\"Condition\":\"webflux-blocking\","
                            + "\"EventLoopLagMillis\":1234}");
        }
    }

    @Test
    void 次元の値に使えない文字を落とす() {
        try (EmbeddedMetricsPublisher publisher = new EmbeddedMetricsPublisher(
                "ns", "Condition", "mvc virtual/jdk21\"",
                List.of(new EmbeddedMetricsPublisher.Metric("Lag", "Milliseconds", () -> 0)),
                5000)) {
            // 引用符がそのまま入ると JSON が壊れ、その行だけ黙って捨てられる
            assertThat(publisher.render(0L)).contains("\"Condition\":\"mvc-virtual-jdk21-\"");
        }
    }

    @Test
    void 条件名が未設定なら_unknown_にする() {
        try (EmbeddedMetricsPublisher publisher = new EmbeddedMetricsPublisher(
                "ns", "Condition", null,
                List.of(new EmbeddedMetricsPublisher.Metric("Lag", "Milliseconds", () -> 0)),
                5000)) {
            assertThat(publisher.render(0L)).contains("\"Condition\":\"unknown\"");
        }
    }
}
