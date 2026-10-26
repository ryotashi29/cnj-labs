package com.ryotashi29.cnj.ai.agentcore.cdk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import software.amazon.awscdk.App;
import software.amazon.awscdk.Environment;
import software.amazon.awscdk.StackProps;
import software.amazon.awscdk.assertions.Template;

class AgentCoreLabStackTest {

    private static Template template;
    private static String json;

    @BeforeAll
    static void synth() {
        App app = new App();
        // アカウントは指定しない（テンプレートの中ではトークンのまま）。リージョンだけ東京にする
        AgentCoreLabStack stack = new AgentCoreLabStack(app, "Test", StackProps.builder()
                .env(Environment.builder().region("ap-northeast-1").build())
                .build());
        template = Template.fromStack(stack);
        json = template.toJSON().toString();
    }

    @Test
    void Runtime_を_2_つと_Memory_と_Gateway_を作る() {
        template.resourceCountIs("AWS::BedrockAgentCore::Runtime", 2);
        template.resourceCountIs("AWS::BedrockAgentCore::Memory", 1);
        template.resourceCountIs("AWS::BedrockAgentCore::Gateway", 1);
        template.resourceCountIs("AWS::BedrockAgentCore::GatewayTarget", 1);
    }

    @Test
    void Runtime_は_Authorization_をコンテナに渡す() {
        template.hasResourceProperties("AWS::BedrockAgentCore::Runtime", Map.of(
                "RequestHeaderConfiguration", Map.of("RequestHeaderAllowlist", java.util.List.of("Authorization"))));
    }

    @Test
    void Memory_の権限はワイルドカードにしない() {
        assertFalse(json.contains("memory/*"), "Memory の権限は作った Memory の ARN に絞る");
    }

    @Test
    void Lambda_の同時実行数は_1() {
        template.hasResourceProperties("AWS::Lambda::Function", Map.of("ReservedConcurrentExecutions", 1));
    }

    @Test
    void 基盤モデルは東京と大阪だけを許可する() {
        assertTrue(json.contains("arn:aws:bedrock:ap-northeast-1::foundation-model/anthropic.claude-haiku-5-5"));
        assertTrue(json.contains("arn:aws:bedrock:ap-northeast-3::foundation-model/anthropic.claude-haiku-5-5"));
        assertFalse(json.contains("arn:aws:bedrock:*::foundation-model"));
        assertEquals(2, AgentCoreLabStack.JP_REGIONS.size());
    }
}
