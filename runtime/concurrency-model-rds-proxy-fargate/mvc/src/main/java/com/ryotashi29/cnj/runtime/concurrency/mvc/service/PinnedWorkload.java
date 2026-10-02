package com.ryotashi29.cnj.runtime.concurrency.mvc.service;

import com.ryotashi29.cnj.runtime.concurrency.common.config.WorkloadProperties;
import com.ryotashi29.cnj.runtime.concurrency.common.dto.WorkloadResult;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;
import org.springframework.stereotype.Service;

/**
 * {@code synchronized} ブロックの中でブロックするワークロード。仮説 2 用。
 *
 * <p>Java 21 では、モニタを保持したままブロックすると仮想スレッドがキャリアスレッドを掴んだままになる
 * （ピニング）。JDK 24 の JEP 491 でこの制約は解消されているため、<b>この検証は実行する JDK の
 * バージョンに強く依存する</b>。Java 21 で測ることに意味があり、結果を読むときは
 * バージョンを明記する必要がある。
 *
 * <p>モニタを 1 本にすると単なる直列化の計測になってしまうので、複数本に分散させている。
 * 狙いはロック競合ではなく、キャリアスレッドが埋まることでスケジューラの並列度が
 * スループットの上限になるかどうかを見ること。上限が
 * {@code jdk.virtualThreadScheduler.parallelism} に一致するのか、
 * それとも {@code maxPoolSize} まで補償されて伸びるのかは実測で確かめる。
 *
 * <p>{@code blockWhileLocked} は<b>同じ失敗の修正版</b>。{@code synchronized} を
 * {@link ReentrantLock} に置き換えただけで、ストライプ本数も保持時間も揃えている。
 * 仮説 2 の対策のうち「隔離」に対応し、仮説 1 の {@code bounded} と同じ位置に立つ。
 */
@Service
public class PinnedWorkload {

    private final WorkloadProperties properties;
    private final Object[] monitors;
    private final ReentrantLock[] locks;
    private final AtomicLong counter = new AtomicLong();

    public PinnedWorkload(WorkloadProperties properties) {
        this.properties = properties;
        int stripes = Math.max(1, properties.lockStripes());
        this.monitors = new Object[stripes];
        this.locks = new ReentrantLock[stripes];
        for (int i = 0; i < stripes; i++) {
            monitors[i] = new Object();
            locks[i] = new ReentrantLock();
        }
    }

    public WorkloadResult blockWhilePinned(Integer requestedMillis) {
        int holdMillis = properties.clampHoldMillis(requestedMillis);
        Object monitor = monitors[nextStripe()];
        synchronized (monitor) {
            NoDatabaseWorkload.sleep(holdMillis);
        }
        return WorkloadResult.of("pinned", holdMillis, 0L, null);
    }

    /**
     * モニタの代わりに {@link ReentrantLock} を使う。<b>JDK 21 でもキャリアスレッドを掴まない。</b>
     *
     * <p>{@code ReentrantLock} は {@code LockSupport.park} で待つため仮想スレッドがアンマウントされ、
     * 待っている間キャリアスレッドが空く。{@code synchronized} はモニタを JVM のスタックに結び付けるので
     * JDK 23 以前ではアンマウントできない。<b>直し方が「JDK を上げる」だけではないことを示すのが狙い。</b>
     * JDK を選べない現場でも、少数のスレッドをブロックしない書き方に変えれば症状は消える。
     *
     * <p>ここで待つのは {@code lock()} ではなく、その中の {@code sleep} であることに注意。
     * ストライプを分散させているのでロック競合はほとんど起きず、
     * 測っているのは<b>ロックを保持したままブロックしたときにキャリアスレッドが解放されるか</b>。
     */
    public WorkloadResult blockWhileLocked(Integer requestedMillis) {
        int holdMillis = properties.clampHoldMillis(requestedMillis);
        ReentrantLock lock = locks[nextStripe()];
        lock.lock();
        try {
            NoDatabaseWorkload.sleep(holdMillis);
        } finally {
            lock.unlock();
        }
        return WorkloadResult.of("pinned-lock", holdMillis, 0L, null);
    }

    private int nextStripe() {
        return (int) Math.floorMod(counter.getAndIncrement(), monitors.length);
    }
}
