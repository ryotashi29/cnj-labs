package com.ryotashi29.cnj.ai.bedrockagent.web;

import com.ryotashi29.cnj.ai.bedrockagent.agent.AgentAnswer;

public record ChatResponse(String conversationId, AgentAnswer answer) {}
