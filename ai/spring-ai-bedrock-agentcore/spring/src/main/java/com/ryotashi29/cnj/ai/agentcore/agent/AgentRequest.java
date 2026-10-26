package com.ryotashi29.cnj.ai.agentcore.agent;

/** {@code POST /invocations} の本文。Strands 版と同じく {@code {"prompt": "..."}}。 */
public record AgentRequest(String prompt) {}
