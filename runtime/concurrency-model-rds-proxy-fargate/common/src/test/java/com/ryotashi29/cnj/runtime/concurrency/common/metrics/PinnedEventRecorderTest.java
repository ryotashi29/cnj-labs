package com.ryotashi29.cnj.runtime.concurrency.common.metrics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.time.Duration;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/**
 * ピニングを件数として拾えることを確かめる。
 *
 * <p>集計そのもの（件数・最長・取り出しで戻ること）と、JFR の購読が本当に成立することを
 * 分けて見ている。前者は時間に依存しないが、後者は<b>動かしている JDK に依存する</b>。
 * JDK 24 以降は {@code synchronized} の中でブロックしてもピニングされない（JEP 491）ため、
 * 発火を期待するテストは 21 でしか成立しない。その差はこの検証の主題そのものなので、
 * 隠さず {@code assumeTrue} で明示している。
 */
class PinnedEventRecorderTest {

    /** JDK 24 でピニングの条件が変わった。この境界より前なら synchronized で発火する。 */
    private static final int PINNING_REMOVED_IN = 24;

    @Test
    void 件数と最長を集計して取り出すと戻る() {
        PinnedEventRecorder recorder = new PinnedEventRecorder(20);

        recorder.record(Duration.ofMillis(120));
        recorder.record(Duration.ofMillis(40));

        assertThat(recorder.takeEventCount()).isEqualTo(2);
        assertThat(recorder.takeMaxDurationMillis()).isEqualTo(120);
        // 区間の値を出す。累計を送ると CloudWatch 側で差分を取る必要が出る
        assertThat(recorder.takeEventCount()).isZero();
        assertThat(recorder.takeMaxDurationMillis()).isZero();
    }

    @Test
    void 購読していないことと件数ゼロを区別できる() {
        PinnedEventRecorder recorder = new PinnedEventRecorder(20);

        // start() を呼んでいない。件数 0 だが「観測していない」状態
        assertThat(recorder.available()).isFalse();
        assertThat(recorder.snapshot())
                .containsEntry("available", false)
                .containsEntry("event", "jdk.VirtualThreadPinned");
    }

    @Test
    void JFRの購読を開始して停止できる() {
        try (PinnedEventRecorder recorder = new PinnedEventRecorder(20)) {
            assertThat(recorder.start())
                    .as("通常の JDK では購読できる。失敗しても例外ではなく false で返ること")
                    .isTrue();
            assertThat(recorder.available()).isTrue();
            assertThat(recorder.snapshot()).containsEntry("status", "稼働中");
        }
    }

    @Test
    void ピニングを実際に拾う() throws Exception {
        assumeTrue(Runtime.version().feature() < PINNING_REMOVED_IN,
                "JDK 24 以降は synchronized でピニングされないので、このテストは成立しない");
        Object lock = new Object();
        try (PinnedEventRecorder recorder = new PinnedEventRecorder(20)) {
            assumeTrue(recorder.start(), "JFR を購読できない環境ではこのテストを行わない");

            // synchronized の中でブロックする。キャリアスレッドを離せないまま待つので
            // jdk.VirtualThreadPinned が出る
            Thread virtual = Thread.ofVirtual().start(() -> {
                synchronized (lock) {
                    sleep(200);
                }
            });
            virtual.join();

            // JFR はバッファを流すまで届かないので、その分を待つ
            assertThat(waitUntilCounted(recorder))
                    .as("ピニングが件数として届くこと")
                    .isTrue();
            assertThat(recorder.takeMaxDurationMillis()).isGreaterThanOrEqualTo(150);
        }
    }

    private static boolean waitUntilCounted(PinnedEventRecorder recorder) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) {
            if (recorder.snapshot().get("pendingEventCount") instanceof Long count && count > 0) {
                return true;
            }
            Thread.sleep(50);
        }
        return false;
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
