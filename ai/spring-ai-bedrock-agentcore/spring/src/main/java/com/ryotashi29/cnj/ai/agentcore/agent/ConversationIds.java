package com.ryotashi29.cnj.ai.agentcore.agent;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * AgentCore Memory の会話 ID（{@code "<actorId>:<sessionId>"}）を組み立てる。
 *
 * <p>actorId（誰の会話か）は、<b>呼び出し側が渡す値ではなく、認証済みの JWT の {@code sub} から取る</b>。
 * SDK の README も、actor の部分は認証済みの主体から導くよう求めている。セッション ID だけで会話を引けると、
 * spring-ai-bedrock-agent で確かめた「会話 ID を知っていれば他人の会話を読める」と同じ問題が残るため。
 *
 * <p>JWT の署名は AgentCore Runtime が検証済みなので、ここでは本文（payload）を読むだけにする。
 */
public final class ConversationIds {

    /** 認証のない手元の実行で使う actorId。 */
    static final String LOCAL_ACTOR = "local";

    private static final Pattern SUB = Pattern.compile("\"sub\"\\s*:\\s*\"([^\"]+)\"");

    private ConversationIds() {}

    public static String of(String authorization, String sessionId) {
        String session = sessionId == null || sessionId.isBlank() ? "default-session" : sessionId;
        return actorId(authorization) + ":" + session;
    }

    /**
     * actorId を決める。{@code Authorization} がなければ手元の実行とみなして {@link #LOCAL_ACTOR} を返す。
     * あるのに読めない場合は、ほかの利用者と同じ actorId にならないよう例外にする。
     */
    static String actorId(String authorization) {
        if (authorization == null || authorization.isBlank()) {
            return LOCAL_ACTOR;
        }
        if (!authorization.regionMatches(true, 0, "Bearer ", 0, 7)) {
            throw new IllegalArgumentException("Authorization が Bearer トークンではありません");
        }
        String[] parts = authorization.substring(7).strip().split("\\.");
        if (parts.length < 2) {
            throw new IllegalArgumentException("JWT の形ではありません");
        }
        String payload = new String(Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8);
        Matcher m = SUB.matcher(payload);
        // actorId に使える文字は限られる。Cognito の sub（UUID）の形だけを受ける
        if (!m.find() || !m.group(1).matches("[A-Za-z0-9-]+")) {
            throw new IllegalArgumentException("JWT に使える sub がありません");
        }
        return m.group(1);
    }
}
