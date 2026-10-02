package com.ryotashi29.cnj.runtime.concurrency.common.diagnostics;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * 判定を間違えると、RDS Proxy が多重化しなかった原因を取り違える。
 * 誤検出も見逃しもそのまま誤った結論になるため、両方向を固定する。
 */
class JdbcUrlsTest {

    private static final String BASE = "jdbc:mysql://proxy:3306/vtlab";

    @Test
    void SERVER_PREP_STMTS_を_true_で組んだ_URL_を有効と読む() {
        assertThat(JdbcUrls.hasServerPrepStmts(
                BASE + "?sslMode=REQUIRED&useServerPrepStmts=true")).isTrue();
    }

    @Test
    void 先頭のパラメータでも読む() {
        assertThat(JdbcUrls.hasServerPrepStmts(
                BASE + "?useServerPrepStmts=true&sslMode=REQUIRED")).isTrue();
    }

    @Test
    void Connector_J_が値の大小文字を区別しないのでこちらも区別しない() {
        assertThat(JdbcUrls.hasServerPrepStmts(BASE + "?useServerPrepStmts=TRUE")).isTrue();
    }

    @Test
    void 既定値を明示した_URL_は無効と読む() {
        assertThat(JdbcUrls.hasServerPrepStmts(
                BASE + "?sslMode=REQUIRED&useServerPrepStmts=false")).isFalse();
    }

    @Test
    void 指定がなければ_Connector_J_の既定に合わせて無効と読む() {
        assertThat(JdbcUrls.hasServerPrepStmts(BASE + "?sslMode=REQUIRED")).isFalse();
    }

    @Test
    void 似た名前のパラメータを取り違えない() {
        // cachePrepStmts はクライアント側のキャッシュで別物。部分一致で拾うと、
        // 有効にしていないのに「ピニングの条件で測った」と読める結果が残る
        assertThat(JdbcUrls.hasServerPrepStmts(BASE + "?cachePrepStmts=true")).isFalse();
        assertThat(JdbcUrls.hasServerPrepStmts(BASE + "?xuseServerPrepStmts=true")).isFalse();
    }

    @Test
    void URL_を取れなかったときは無効として扱う() {
        assertThat(JdbcUrls.hasServerPrepStmts(null)).isFalse();
    }
}
