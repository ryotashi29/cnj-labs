package com.ryotashi29.cnj.ai.bedrockagent.web;

/** 会話 ID を省略すると、新しい会話として扱う。 */
public record ChatRequest(String conversationId, String message) {}
