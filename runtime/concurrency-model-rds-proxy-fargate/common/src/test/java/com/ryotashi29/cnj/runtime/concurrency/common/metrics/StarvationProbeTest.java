package com.ryotashi29.cnj.runtime.concurrency.common.metrics;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/**
 * ブロックされた executor を観測できることを確かめる。
 *
 * <p>ここで守りたいのは <b>「タスクが走らないこと」を遅延として報告する</b>という一点。
 * 完了時にだけ計測すると、完全にブロックされた瞬間の標本がゼロになって値が出ない。
 * その実装だと AWS で一番見たい瞬間が空になるので、テストで固定しておく。
 */
class StarvationProbeTest {

    @Test
    void ブロックされたスレッドの遅延が伸びる() throws Exception {
        ExecutorService target = Executors.newSingleThreadExecutor();
        CountDownLatch release = new CountDownLatch(1);
        // 監視対象の唯一のスレッドを掴んで離さない。イベントループがブロックされた状態の再現
        target.execute(() -> await(release));

        try (StarvationProbe probe = new StarvationProbe("test", Map.of("single", target), 10)) {
            probe.start();
            assertThat(waitUntilLagAtLeast(probe, 100))
                    .as("ブロックされている間、投げたタスクは走らない。それでも遅延が伸びること")
                    .isTrue();
            // 未完了でも高水位に載る。送信側はこちらを読む
            assertThat(probe.takeMaxLagMillis()).isGreaterThanOrEqualTo(100);
        } finally {
            release.countDown();
            target.shutdownNow();
        }
    }

    @Test
    void 空いているスレッドの遅延は伸びない() throws Exception {
        Executor target = Runnable::run;
        try (StarvationProbe probe = new StarvationProbe("test", Map.of("inline", target), 10)) {
            probe.start();
            Thread.sleep(200);
            assertThat(probe.lagMillis()).isLessThan(50);
            assertThat(probe.snapshot()).containsEntry("resource", "test");
        }
    }

    @Test
    void 監視したスレッドの名前を申告する() throws Exception {
        ExecutorService target = Executors.newSingleThreadExecutor(
                runnable -> new Thread(runnable, "watched-thread"));
        try (StarvationProbe probe = new StarvationProbe("test", Map.of("single", target), 10)) {
            probe.start();
            Thread.sleep(200);
            // 狙ったスレッドを監視できているかを、推測ではなくこの値で確かめる
            assertThat(probe.snapshot().toString()).contains("watched-thread");
        } finally {
            target.shutdownNow();
        }
    }

    private static boolean waitUntilLagAtLeast(StarvationProbe probe, long millis) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) {
            if (probe.lagMillis() >= millis) {
                return true;
            }
            Thread.sleep(10);
        }
        return false;
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
