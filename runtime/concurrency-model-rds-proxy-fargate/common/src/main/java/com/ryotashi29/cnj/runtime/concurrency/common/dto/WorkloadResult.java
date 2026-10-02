package com.ryotashi29.cnj.runtime.concurrency.common.dto;

/**
 * 1 リクエストの実行結果。負荷試験のレスポンスボディとしてだけでなく、
 * 単発の curl で「いま仮想スレッドで動いているか」「バックエンド接続が多重化されているか」を確かめる用途も兼ねる。
 *
 * @param mode                実行したワークロードの種類
 * @param holdMillis          実際に保持した時間（クランプ後）
 * @param thread              実行スレッドの文字列表現。仮想スレッドならキャリアスレッド名まで含まれる
 * @param virtualThread       仮想スレッド上で動いたか
 * @param permitWaitMillis    同時実行許可の取得に待った時間。{@code bounded} 以外は 0
 * @param backendConnectionId MySQL 側の接続 ID。DB を使わないワークロードでは null
 */
public record WorkloadResult(
        String mode,
        int holdMillis,
        String thread,
        boolean virtualThread,
        long permitWaitMillis,
        Long backendConnectionId) {

    public static WorkloadResult of(String mode, int holdMillis, long permitWaitMillis, Long backendConnectionId) {
        Thread current = Thread.currentThread();
        return new WorkloadResult(mode, holdMillis, current.toString(), current.isVirtual(),
                permitWaitMillis, backendConnectionId);
    }
}
