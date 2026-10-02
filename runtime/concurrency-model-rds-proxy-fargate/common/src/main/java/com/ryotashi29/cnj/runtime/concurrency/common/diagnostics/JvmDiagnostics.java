package com.ryotashi29.cnj.runtime.concurrency.common.diagnostics;

import java.lang.management.ManagementFactory;
import java.lang.management.MemoryUsage;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 両スタックが同じ方法で申告する JVM の状態。
 *
 * <p>3 つの並行モデルの本当の差はスループットよりメモリに出る。Fargate ではタスクサイズが
 * そのままコストなので、運用コストの判断にそのまま使える数字になる。
 * mvc / webflux で測り方が違うと比較にならないため、共有モジュールに置いている。
 *
 * <p><b>ここで返すのはヒープと非ヒープ（メタスペース等）であって RSS ではない。</b>
 * RSS には JVM 自身のオーバーヘッド、スレッドスタック、Netty のダイレクトバッファが含まれ、
 * プロセスの外から見ないと正確に取れない。RSS は {@code k6/sweep.sh} が {@code ps} で
 * 外部からサンプリングする。プロセス内で測ると測定自体がメモリを動かすうえ、
 * macOS と Linux で取得方法が変わってしまう。
 */
public final class JvmDiagnostics {

    private JvmDiagnostics() {
    }

    public static Map<String, Object> memory() {
        var memoryBean = ManagementFactory.getMemoryMXBean();
        Map<String, Object> memory = new LinkedHashMap<>();
        memory.put("heap", usage(memoryBean.getHeapMemoryUsage()));
        memory.put("nonHeap", usage(memoryBean.getNonHeapMemoryUsage()));
        memory.put("liveThreads", ManagementFactory.getThreadMXBean().getThreadCount());
        return memory;
    }

    private static Map<String, Object> usage(MemoryUsage usage) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("usedMb", toMb(usage.getUsed()));
        result.put("committedMb", toMb(usage.getCommitted()));
        result.put("maxMb", usage.getMax() < 0 ? null : toMb(usage.getMax()));
        return result;
    }

    private static long toMb(long bytes) {
        return bytes / (1024 * 1024);
    }

    /**
     * 名前が接頭辞に一致する生存スレッド数。
     *
     * <p>イベントループ（{@code reactor-http-nio-}）やキャリアスレッド（{@code ForkJoinPool}）が
     * 実際に何本立っているかを見るために使う。仮説 2 の「数の限られたスレッドの本数が上限を決める」は
     * この値が {@code availableProcessors()} に連動することが前提なので、推測せず実測する。
     */
    public static long countThreads(String namePrefix) {
        return Thread.getAllStackTraces().keySet().stream()
                .filter(thread -> thread.getName().startsWith(namePrefix))
                .count();
    }
}
