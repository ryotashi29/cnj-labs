package com.ryotashi29.cnj.ai.agentcore.cdk;

import software.amazon.awscdk.App;
import software.amazon.awscdk.Environment;
import software.amazon.awscdk.StackProps;

public class CdkApp {

    public static void main(String[] args) {
        App app = new App();
        // アカウントとリージョンは、デプロイするときの認証情報（CDK_DEFAULT_*）から取る。コードには書かない
        new AgentCoreLabStack(app, "CnjAgentCoreLab", StackProps.builder()
                .env(Environment.builder()
                        .account(System.getenv("CDK_DEFAULT_ACCOUNT"))
                        .region(System.getenv("CDK_DEFAULT_REGION"))
                        .build())
                .description("cnj-labs ai/spring-ai-bedrock-agentcore の検証環境（使い捨て）")
                .build());
        app.synth();
    }
}
