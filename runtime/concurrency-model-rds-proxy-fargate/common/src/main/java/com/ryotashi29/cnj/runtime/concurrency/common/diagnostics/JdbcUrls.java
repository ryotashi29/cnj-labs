package com.ryotashi29.cnj.runtime.concurrency.common.diagnostics;

import java.util.regex.Pattern;

/**
 * JDBC URL から計測条件を読み取る。
 *
 * <p>両スタックが同じ URL の組み立て方（{@code SERVER_PREP_STMTS} を読む）をしており、
 * 申告の判定が片方だけずれると「RDS Proxy が多重化しなかった」の原因を取り違える。
 * 判定を 1 箇所に置いて、規律ではなくコンパイルで揃えている。
 */
public final class JdbcUrls {

    /**
     * Connector/J のデフォルトは {@code false} なので、URL に現れていなければ無効と読む。
     * 値の大小文字は Connector/J が区別しないため、こちらも区別しない。
     */
    private static final Pattern SERVER_PREP_STMTS =
            Pattern.compile("[?&]useServerPrepStmts=true", Pattern.CASE_INSENSITIVE);

    private JdbcUrls() {
    }

    /**
     * サーバ側プリペアドステートメントが有効か。
     *
     * <p><b>RDS Proxy のセッションピニングの原因になる。</b>有効だとプロキシは接続を多重化できず、
     * クライアント接続とバックエンド接続が 1:1 に張り付く。多重化の結果を読むときは
     * 必ずこの値と突き合わせる必要があるため、スイッチの値ではなく実効の URL から判定する。
     */
    public static boolean hasServerPrepStmts(String jdbcUrl) {
        return jdbcUrl != null && SERVER_PREP_STMTS.matcher(jdbcUrl).find();
    }
}
