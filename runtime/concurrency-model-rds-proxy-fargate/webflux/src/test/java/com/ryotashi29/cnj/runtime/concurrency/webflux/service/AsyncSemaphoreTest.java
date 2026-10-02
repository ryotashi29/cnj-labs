package com.ryotashi29.cnj.runtime.concurrency.webflux.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import reactor.core.Disposable;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import reactor.test.StepVerifier;

/**
 * 許可のリークを検出することが主目的のテスト。
 *
 * <p>リークするとスループットが計測の途中から静かに落ちていく。原因が結果に現れないため、
 * 「WebFlux は同時数を上げると劣化する」という<b>誤った結論を出してしまう</b>種類のバグになる。
 * 計測器としての信頼性を担保するために、正常系よりも競合と後片付けを重点的に検証している。
 */
class AsyncSemaphoreTest {

    @Test
    void 空きがあれば即座に許可される() {
        AsyncSemaphore semaphore = new AsyncSemaphore(2);

        StepVerifier.create(semaphore.acquire()).verifyComplete();

        assertThat(semaphore.availablePermits()).isEqualTo(1);
    }

    @Test
    void 空きがなければ完了せず待ちに積まれる() {
        AsyncSemaphore semaphore = new AsyncSemaphore(1);
        semaphore.acquire().block(Duration.ofSeconds(1));

        AtomicInteger completed = new AtomicInteger();
        Disposable waiting = semaphore.acquire().doOnSuccess(ignored -> completed.incrementAndGet()).subscribe();

        assertThat(completed).hasValue(0);
        assertThat(semaphore.queueLength()).isEqualTo(1);

        waiting.dispose();
    }

    @Test
    void release_は待っている呼び出しへ直接渡す() {
        AsyncSemaphore semaphore = new AsyncSemaphore(1);
        semaphore.acquire().block(Duration.ofSeconds(1));

        AtomicInteger completed = new AtomicInteger();
        semaphore.acquire().doOnSuccess(ignored -> completed.incrementAndGet()).subscribe();

        semaphore.release();

        assertThat(completed).hasValue(1);
        // 待っていた側へ渡したので、空きに戻してはいけない
        assertThat(semaphore.availablePermits()).isZero();
        assertThat(semaphore.queueLength()).isZero();
    }

    @Test
    void タイムアウトした待ちは待ち行列から外れ許可も減らさない() {
        AsyncSemaphore semaphore = new AsyncSemaphore(1);
        semaphore.acquire().block(Duration.ofSeconds(1));

        StepVerifier.create(semaphore.acquire().timeout(Duration.ofMillis(50)))
                .verifyError(TimeoutException.class);

        assertThat(semaphore.queueLength()).isZero();
        assertThat(semaphore.availablePermits()).isZero();

        // 使用中の 1 つを返すと空きに戻る。タイムアウトした側が許可を持ち去っていないことの確認
        semaphore.release();
        assertThat(semaphore.availablePermits()).isEqualTo(1);
    }

    @Test
    void タイムアウトと_release_が競合しても許可はリークしない() {
        // 許可 1 つに対して大量の待ちをぶつけ、保持時間よりタイムアウトを短くする。
        // これで「release が待ちへ許可を渡した瞬間にその待ちがキャンセルされる」競合が繰り返し起きる。
        // どちら側が CAS に勝ったかをテストから観測しようとすると、テスト自身が競合を持ち込んで
        // しまうので、最後に許可が全部戻っているという不変条件だけを見る
        int permits = 1;
        int operations = 500;
        AsyncSemaphore semaphore = new AsyncSemaphore(permits);
        AtomicInteger succeeded = new AtomicInteger();
        AtomicInteger timedOut = new AtomicInteger();

        List<Mono<Void>> attempts = new ArrayList<>();
        for (int i = 0; i < operations; i++) {
            attempts.add(Mono.usingWhen(
                            semaphore.acquire()
                                    .timeout(Duration.ofMillis(1))
                                    .thenReturn(new Object()),
                            ignored -> Mono.delay(Duration.ofMillis(2))
                                    .doOnSuccess(unused -> succeeded.incrementAndGet())
                                    .then(),
                            ignored -> Mono.fromRunnable(semaphore::release))
                    .onErrorResume(TimeoutException.class,
                            e -> Mono.fromRunnable(timedOut::incrementAndGet))
                    .subscribeOn(Schedulers.parallel()));
        }

        Mono.when(attempts).block(Duration.ofSeconds(60));

        assertThat(succeeded.get() + timedOut.get()).isEqualTo(operations);
        assertThat(succeeded.get()).isPositive();
        // 保持 2 ms に対してタイムアウト 1 ms なので、待たされた側は必ず溢れる。
        // ここが 0 だと競合が起きておらず、テストが何も検証していないことになる
        assertThat(timedOut.get()).isPositive();
        assertThat(semaphore.queueLength()).isZero();
        // 1 つでもリークしていればここが permits より小さくなる
        assertThat(semaphore.availablePermits()).isEqualTo(permits);
    }

    @Test
    void 同時に大量の取得と返却を繰り返しても許可の総数は保たれる() throws Exception {
        int permits = 8;
        int tasks = 500;
        AsyncSemaphore semaphore = new AsyncSemaphore(permits);

        List<Mono<Void>> operations = new ArrayList<>();
        for (int i = 0; i < tasks; i++) {
            operations.add(Mono.usingWhen(
                    semaphore.acquire().timeout(Duration.ofSeconds(5)).thenReturn(new Object()),
                    ignored -> Mono.delay(Duration.ofMillis(1)).then(),
                    ignored -> Mono.fromRunnable(semaphore::release)));
        }

        Mono.when(operations).block(Duration.ofSeconds(30));

        assertThat(semaphore.availablePermits()).isEqualTo(permits);
        assertThat(semaphore.queueLength()).isZero();
    }
}
