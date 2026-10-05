package com.ryotashi29.cnj.runtime.concurrency.webflux.service;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;
import reactor.core.publisher.Mono;
import reactor.core.publisher.MonoSink;

/**
 * イベントループをブロックせずに同時実行数を制限するセマフォ。
 *
 * <p>MVC 側は {@code java.util.concurrent.Semaphore#tryAcquire(timeout, unit)} の 1 行で済む。
 * リアクティブでは待ちをスレッドのブロックで表現できないため、待っている呼び出しを
 * {@link MonoSink} として保持し、許可が空いたら渡す形に書き直す必要がある。
 * <b>「明示的なバックプレッシャを書くコストがモデルによってどれだけ違うか」自体が計測対象</b>なので、
 * {@code Semaphore} を {@code boundedElastic} に逃がす実装（下記）にはしていない。
 *
 * <p>{@code Semaphore#tryAcquire} を {@code Schedulers.boundedElastic()} に載せる手もあるが、
 * それだと待っている数だけ実スレッドが必要になる。boundedElastic のデフォルト上限は
 * {@code 10 × コア数} なので、同時 800 の段でスレッド待ちが発生し、測りたいものではなく
 * スケジューラの詰まりを測ってしまう。
 *
 * <h2>タイムアウトの扱い</h2>
 * 待ち時間の上限はこのクラスでは持たず、呼び出し側が {@code acquire().timeout(d)} で与える。
 * タイムアウトは上流のキャンセルとして届くため、クライアント切断も同じ経路で処理できる。
 *
 * <p>キャンセルと {@link #release()} は competing する。両者を {@code AtomicBoolean} の CAS で
 * 決着させ、負けた側が後片付けをする。ここを間違えると許可が 1 つずつ失われ、
 * 負荷試験の途中から静かにスループットが落ちていく（原因が計測結果に見えない種類のバグ）。
 */
final class AsyncSemaphore {

    private final ReentrantLock lock = new ReentrantLock();
    private final Deque<Waiter> waiters = new ArrayDeque<>();
    private int available;

    AsyncSemaphore(int permits) {
        this.available = permits;
    }

    /**
     * 許可を 1 つ取る。空きがなければ完了しない {@link Mono} を返すので、
     * 呼び出し側が {@code timeout} で上限を与える。
     */
    Mono<Void> acquire() {
        return Mono.create(sink -> {
            Waiter waiter = new Waiter(sink);
            boolean granted;
            lock.lock();
            try {
                granted = available > 0;
                if (granted) {
                    available--;
                } else {
                    waiters.addLast(waiter);
                }
            } finally {
                lock.unlock();
            }
            if (granted) {
                // ロックの外で成功を通知する。ロックを持ったまま下流を走らせると、
                // 下流の release() が同じロックを取りに来て詰まる
                sink.success();
                return;
            }
            sink.onCancel(() -> onWaiterCancelled(waiter));
        });
    }

    /** 許可を返す。待っている呼び出しがあれば、空きに戻さずそのまま渡す。 */
    void release() {
        Waiter next = null;
        lock.lock();
        try {
            // すでにキャンセル済みの待ちは飛ばす。claim に負けたものは対象外
            while (next == null) {
                Waiter candidate = waiters.pollFirst();
                if (candidate == null) {
                    available++;
                    break;
                }
                if (candidate.claim()) {
                    next = candidate;
                }
            }
        } finally {
            lock.unlock();
        }
        if (next != null) {
            next.sink.success();
        }
    }

    int availablePermits() {
        lock.lock();
        try {
            return available;
        } finally {
            lock.unlock();
        }
    }

    /** 待ちの数。診断エンドポイントで「溢れているのか、単に遅いのか」を切り分けるために覗く。 */
    int queueLength() {
        lock.lock();
        try {
            return waiters.size();
        } finally {
            lock.unlock();
        }
    }

    private void onWaiterCancelled(Waiter waiter) {
        boolean claimedByUs;
        lock.lock();
        try {
            claimedByUs = waiter.claim();
            if (claimedByUs) {
                waiters.remove(waiter);
            }
        } finally {
            lock.unlock();
        }
        if (!claimedByUs) {
            // release() が先に許可を渡していた。こちらはキャンセル済みで使えないので返却する。
            // これを忘れると許可が 1 つ消える
            release();
        }
    }

    private static final class Waiter {
        private final MonoSink<Void> sink;
        private final AtomicBoolean settled = new AtomicBoolean();

        private Waiter(MonoSink<Void> sink) {
            this.sink = sink;
        }

        private boolean claim() {
            return settled.compareAndSet(false, true);
        }
    }
}
