package com.ryotashi29.cnj.ai.agentcore.agent;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springaicommunity.agentcore.annotation.AgentCoreInvocation;
import org.springaicommunity.agentcore.context.AgentCoreContext;
import org.springaicommunity.agentcore.context.AgentCoreHeaders;
import org.springframework.stereotype.Component;

/**
 * AgentCore Runtime の入口。
 *
 * <p>{@link AgentCoreInvocation} を付けたメソッドが、runtime-starter の {@code POST /invocations} につながる（{@code GET
 * /ping} も自動で用意される）。Strands 版の {@code @app.entrypoint} に当たる。
 */
@Component
public class AgentInvocation {

    private static final Logger log = LoggerFactory.getLogger(AgentInvocation.class);

    private final OutingAgent agent;

    public AgentInvocation(OutingAgent agent) {
        this.agent = agent;
    }

    @AgentCoreInvocation
    public AgentAnswer invoke(AgentRequest request, AgentCoreContext context) {
        String sessionId = context.getHeader(AgentCoreHeaders.SESSION_ID);
        String authorization = context.getHeader(AgentCoreHeaders.AUTHORIZATION);
        String conversationId = ConversationIds.of(authorization, sessionId);
        log.info("invoke: sessionId={}", sessionId);
        String prompt = request == null || request.prompt() == null ? "" : request.prompt();
        return agent.chat(conversationId, prompt, authorization);
    }
}
