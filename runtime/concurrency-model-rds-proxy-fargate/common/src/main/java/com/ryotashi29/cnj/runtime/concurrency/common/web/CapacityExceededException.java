package com.ryotashi29.cnj.runtime.concurrency.common.web;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/**
 * 同時実行の許可を取れなかったことを表す。
 *
 * <p>接続待ちでタイムアウトして 500 になるのではなく、上限に達したことを 503 として明示的に返すための例外。
 * 「仮想スレッドで外れた同時実行制限をアプリ側で張り直す」という結論の実装側の要点なので、
 * 汎用の例外ではなく専用の型にしている。
 */
@ResponseStatus(HttpStatus.SERVICE_UNAVAILABLE)
public class CapacityExceededException extends RuntimeException {

    public CapacityExceededException(int permits, long waitedMillis) {
        super("同時実行の上限 %d に達し、%d ms 待っても許可を取得できませんでした".formatted(permits, waitedMillis));
    }
}
