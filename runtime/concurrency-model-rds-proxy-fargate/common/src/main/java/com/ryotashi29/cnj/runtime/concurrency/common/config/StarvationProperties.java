package com.ryotashi29.cnj.runtime.concurrency.common.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 数の限られたスレッド（キャリアスレッド・イベントループ）の枯渇を観測するための調整値。仮説 2 の対策のうち「観測」に対応する。
 *
 * <p>mvc / webflux で値がずれると条件間の比較が成立しないため、{@link WorkloadProperties} と
 * 同じ理由で共有モジュールに置いている。
 *
 * @param enabled                観測を行うか。ローカルで stdout を汚したくないときに切る
 * @param probeIntervalMillis    監視タスクを投げる間隔。負荷が 70 秒しかないので秒より細かく見る
 * @param publishIntervalMillis  EMF を stdout に吐く間隔
 * @param namespace              CloudWatch の名前空間
 * @param condition              CloudWatch の次元の値。{@code measure-aws.sh} が条件名を渡す
 * @param pinnedThresholdMillis  ピニングを数える下限。これより短いものは害がないので数えない。
 *                               仮想スレッドを使う mvc 側だけが使う値だが、両モジュールで
 *                               デフォルトを揃えておかないと条件間で閾値がずれる
 */
@ConfigurationProperties(prefix = "starvation")
public record StarvationProperties(
        boolean enabled,
        long probeIntervalMillis,
        long publishIntervalMillis,
        String namespace,
        String condition,
        long pinnedThresholdMillis) {
}
