package com.ryotashi29.cnj.ai.agentcore.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import org.junit.jupiter.api.Test;

class ConversationIdsTest {

    private static String bearer(String payloadJson) {
        Base64.Encoder enc = Base64.getUrlEncoder().withoutPadding();
        return "Bearer " + enc.encodeToString("{\"alg\":\"RS256\"}".getBytes(StandardCharsets.UTF_8)) + "."
                + enc.encodeToString(payloadJson.getBytes(StandardCharsets.UTF_8)) + ".sig";
    }

    @Test
    void actorId_は_JWT_の_sub_から取る() {
        String auth = bearer("{\"sub\":\"user-sub-abc-1\",\"aud\":\"x\"}");
        assertThat(ConversationIds.of(auth, "session-1"))
                .isEqualTo("user-sub-abc-1:session-1");
    }

    @Test
    void Authorization_がなければ手元の実行として扱う() {
        assertThat(ConversationIds.of(null, "s")).isEqualTo("local:s");
        assertThat(ConversationIds.of("", null)).isEqualTo("local:default-session");
    }

    @Test
    void Authorization_があるのに読めなければ例外にする() {
        assertThatThrownBy(() -> ConversationIds.of("Basic abc", "s")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ConversationIds.of(bearer("{\"aud\":\"x\"}"), "s"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ConversationIds.of(bearer("{\"sub\":\"a:b\"}"), "s"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
