package com.ryotashi29.cnj.ai.bedrockagent.web;

import com.ryotashi29.cnj.ai.bedrockagent.agent.OutingAgent;
import java.util.UUID;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class ChatController {

    private final OutingAgent agent;

    public ChatController(OutingAgent agent) {
        this.agent = agent;
    }

    /**
     * 会話する。
     *
     * <p>会話 ID は呼び出し側が渡した値をそのまま使う。誰の会話かは確かめていない（認証がない）。
     */
    @PostMapping("/chat")
    public ChatResponse chat(@RequestBody ChatRequest request) {
        String conversationId = request.conversationId() == null || request.conversationId().isBlank()
                ? UUID.randomUUID().toString()
                : request.conversationId();
        return new ChatResponse(conversationId, agent.chat(conversationId, request.message()));
    }
}
