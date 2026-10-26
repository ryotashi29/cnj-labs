package com.ryotashi29.cnj.ai.agentcore.cdk;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import software.amazon.awscdk.CfnOutput;
import software.amazon.awscdk.Duration;
import software.amazon.awscdk.RemovalPolicy;
import software.amazon.awscdk.Stack;
import software.amazon.awscdk.StackProps;
import software.amazon.awscdk.services.bedrockagentcore.AddLambdaTargetOptions;
import software.amazon.awscdk.services.bedrockagentcore.AgentRuntimeArtifact;
import software.amazon.awscdk.services.bedrockagentcore.CustomJwtConfiguration;
import software.amazon.awscdk.services.bedrockagentcore.Gateway;
import software.amazon.awscdk.services.bedrockagentcore.GatewayAuthorizer;
import software.amazon.awscdk.services.bedrockagentcore.GatewayProps;
import software.amazon.awscdk.services.bedrockagentcore.LogType;
import software.amazon.awscdk.services.bedrockagentcore.LoggingConfig;
import software.amazon.awscdk.services.bedrockagentcore.LoggingDestination;
import software.amazon.awscdk.services.bedrockagentcore.ManagedStrategyProps;
import software.amazon.awscdk.services.bedrockagentcore.Memory;
import software.amazon.awscdk.services.bedrockagentcore.MemoryProps;
import software.amazon.awscdk.services.bedrockagentcore.MemoryStrategy;
import software.amazon.awscdk.services.bedrockagentcore.ProtocolType;
import software.amazon.awscdk.services.bedrockagentcore.RequestHeaderConfiguration;
import software.amazon.awscdk.services.bedrockagentcore.Runtime;
import software.amazon.awscdk.services.bedrockagentcore.RuntimeAuthorizerConfiguration;
import software.amazon.awscdk.services.bedrockagentcore.RuntimeNetworkConfiguration;
import software.amazon.awscdk.services.bedrockagentcore.RuntimeProps;
import software.amazon.awscdk.services.bedrockagentcore.SchemaDefinition;
import software.amazon.awscdk.services.bedrockagentcore.SchemaDefinitionType;
import software.amazon.awscdk.services.bedrockagentcore.ToolDefinition;
import software.amazon.awscdk.services.bedrockagentcore.ToolSchema;
import software.amazon.awscdk.services.cognito.AuthFlow;
import software.amazon.awscdk.services.cognito.UserPool;
import software.amazon.awscdk.services.cognito.UserPoolClient;
import software.amazon.awscdk.services.ecr.assets.DockerImageAssetOptions;
import software.amazon.awscdk.services.ecr.assets.Platform;
import software.amazon.awscdk.services.iam.Effect;
import software.amazon.awscdk.services.iam.PolicyStatement;
import software.amazon.awscdk.services.lambda.Code;
import software.amazon.awscdk.services.lambda.Function;
import software.amazon.awscdk.services.lambda.Tracing;
import software.amazon.awscdk.services.logs.LogGroup;
import software.amazon.awscdk.services.logs.RetentionDays;
import software.constructs.Construct;

/**
 * この検証の環境。同じエージェントの Spring AI 版と Strands 版を、AgentCore の上で並べて動かす。
 *
 * <ul>
 *   <li>Cognito：利用者の認証。ID トークン（JWT）を Runtime と Gateway の両方で検証する
 *   <li>Gateway：地名から座標を引くツール（Lambda）を、MCP のツールとして公開する。2 つの版が同じツールを使う
 *   <li>Memory：会話の短期メモリと、長期メモリ（好み・要約）。2 つの版が同じ Memory を使う
 *   <li>Runtime：Spring AI 版と Strands 版の 2 つ。同じ環境変数・同じ権限で動かす
 * </ul>
 *
 * <p>検証用の使い捨ての環境なので、削除の保護は付けず、スタックを消すとすべて消える（{@link RemovalPolicy#DESTROY}）。
 */
public class AgentCoreLabStack extends Stack {

    /** ツール名。2 つの版のツール名・Lambda の {@code TOOL_NAME} と揃える。 */
    static final String TOOL_NAME = "resolve_place_coordinates";

    /** 両方の版のコードの既定値と揃える。context {@code modelId} で変えられる。 */
    static final String DEFAULT_MODEL_ID = "jp.anthropic.claude-haiku-5-5";

    /**
     * {@code jp.} の推論プロファイルが処理を振り分ける先のリージョン（東京と大阪）。
     *
     * <p>推論プロファイルの ARN だけを許可すると、振り分け先の基盤モデルの呼び出しで AccessDenied になるので、両方を許可する。
     */
    static final List<String> JP_REGIONS = List.of("ap-northeast-1", "ap-northeast-3");

    /** 長期メモリの名前空間。Strands 版の {@code AGENTCORE_MEMORY_NAMESPACES} と揃える（Spring AI 版は AWS から読む）。 */
    static final String PREFERENCE_NAMESPACE = "/preferences/{actorId}";

    static final String SUMMARY_NAMESPACE = "/summaries/{actorId}/{sessionId}";

    /** {@code usingJWT} と Gateway の JWT 設定は、discoveryUrl がこのパスで終わることを synth のときに確かめる。 */
    private static final String OIDC_DISCOVERY_PATH = "/.well-known/openid-configuration";

    public AgentCoreLabStack(Construct scope, String id, StackProps props) {
        super(scope, id, props);
        String modelId = contextString("modelId", DEFAULT_MODEL_ID);

        // --- Cognito（利用者の認証） ---
        UserPool userPool = UserPool.Builder.create(this, "UserPool")
                .userPoolName("cnj-agentcore-lab")
                // 利用者は scripts/create-user.sh で管理者が作る。自分で登録はさせない
                .selfSignUpEnabled(false)
                .removalPolicy(RemovalPolicy.DESTROY)
                .build();
        UserPoolClient client = UserPoolClient.Builder.create(this, "UserPoolClient")
                .userPool(userPool)
                .generateSecret(false)
                // Hosted UI（OAuth のフロー）は使わない。CDK のデフォルトでは implicit と code のフローが
                // コールバック https://example.com で有効になるので、明示して無効にする
                .disableOAuth(true)
                // ユーザー名が存在するかどうかを、ログインの失敗のメッセージから推測させない
                .preventUserExistenceErrors(true)
                // 検証用のスクリプトから、ユーザー名とパスワードで ID トークンを取る
                .authFlows(AuthFlow.builder().userPassword(true).build())
                .idTokenValidity(Duration.hours(1))
                .build();
        String issuer = "https://cognito-idp." + getRegion() + ".amazonaws.com/" + userPool.getUserPoolId();
        String discoveryUrl = issuer + OIDC_DISCOVERY_PATH;
        // ID トークンは client_id ではなく aud を持つので、aud（＝アプリクライアントの ID）で検証する。
        // usingCognito は client_id を確かめる設定（allowedClients）を必ず入れるため、ID トークンが 401 になる
        List<String> audience = List.of(client.getUserPoolClientId());

        // --- Gateway（ツール） ---
        Function geocoding = geocodingFunction();
        Gateway gateway = new Gateway(this, "Gateway", GatewayProps.builder()
                .gatewayName("cnj-agentcore-lab-tools")
                .description("地名から座標を引くツール。Spring AI 版と Strands 版の両方が使う")
                .authorizerConfiguration(GatewayAuthorizer.usingCustomJwt(CustomJwtConfiguration.builder()
                        .discoveryUrl(discoveryUrl)
                        .allowedAudience(audience)
                        .build()))
                .build());
        gateway.addLambdaTarget("Geocoding", AddLambdaTargetOptions.builder()
                .gatewayTargetName("geocoding")
                .description("Nominatim で地名から座標を引く")
                .lambdaFunction(geocoding)
                .toolSchema(ToolSchema.fromInline(List.of(toolDefinition())))
                .build());

        // --- Memory（会話のメモリ） ---
        Memory memory = new Memory(this, "Memory", MemoryProps.builder()
                .memoryName("cnj_agentcore_lab_memory")
                .description("会話の短期メモリと、長期メモリ（好み・要約）")
                .expirationDuration(Duration.days(7))
                .memoryStrategies(List.of(
                        MemoryStrategy.usingUserPreference(ManagedStrategyProps.builder()
                                .strategyName("preferences")
                                .namespaces(List.of(PREFERENCE_NAMESPACE))
                                .build()),
                        MemoryStrategy.usingSummarization(ManagedStrategyProps.builder()
                                .strategyName("summaries")
                                .namespaces(List.of(SUMMARY_NAMESPACE))
                                .build())))
                .build());

        // --- Runtime（2 つの版） ---
        Map<String, String> env = new LinkedHashMap<>();
        env.put("BEDROCK_MODEL_ID", modelId);
        env.put("AWS_REGION", getRegion());
        env.put("GATEWAY_URL", gateway.getGatewayUrl());
        env.put("AGENTCORE_MEMORY_ID", memory.getMemoryId());
        env.put("AGENTCORE_MEMORY_NAMESPACES", PREFERENCE_NAMESPACE + "," + SUMMARY_NAMESPACE);

        Map<String, String> springEnv = new LinkedHashMap<>(env);
        // Memory を使う設定（application-agentcore.yml）を有効にする
        springEnv.put("SPRING_PROFILES_ACTIVE", "agentcore");
        // ADOT の Java エージェントの送り先。Python の ADOT と違い、Runtime は Java の送り先を入れないので自分で書く。
        // ログはコンテナの標準出力が CloudWatch Logs に届いているので、OTLP では送らない
        springEnv.put("OTEL_EXPORTER_OTLP_PROTOCOL", "http/protobuf");
        springEnv.put("OTEL_TRACES_EXPORTER", "otlp");
        springEnv.put("OTEL_EXPORTER_OTLP_TRACES_ENDPOINT", "https://xray." + getRegion() + ".amazonaws.com/v1/traces");
        springEnv.put("OTEL_METRICS_EXPORTER", "none");
        springEnv.put("OTEL_LOGS_EXPORTER", "none");

        Runtime spring = runtime("SpringRuntime", "cnj_agentcore_lab_spring", "../spring",
                "Spring AI 版", springEnv, discoveryUrl, audience, memory, modelId);
        Runtime strands = runtime("StrandsRuntime", "cnj_agentcore_lab_strands", "../strands",
                "Strands 版", env, discoveryUrl, audience, memory, modelId);

        output("UserPoolId", userPool.getUserPoolId());
        output("UserPoolClientId", client.getUserPoolClientId());
        output("GatewayUrl", gateway.getGatewayUrl());
        output("MemoryId", memory.getMemoryId());
        output("SpringRuntimeArn", spring.getAgentRuntimeArn());
        output("StrandsRuntimeArn", strands.getAgentRuntimeArn());
    }

    private Runtime runtime(
            String id,
            String runtimeName,
            String dockerContext,
            String description,
            Map<String, String> env,
            String discoveryUrl,
            List<String> audience,
            Memory memory,
            String modelId) {
        // L2 が実行ロールに付けるログの権限は /aws/bedrock-agentcore/runtimes/ の下に限られるので、そこに置く
        LogGroup logs = LogGroup.Builder.create(this, id + "Logs")
                .logGroupName("/aws/bedrock-agentcore/runtimes/" + runtimeName)
                .retention(RetentionDays.ONE_WEEK)
                .removalPolicy(RemovalPolicy.DESTROY)
                .build();

        Runtime runtime = new Runtime(this, id, RuntimeProps.builder()
                .runtimeName(runtimeName)
                .description(description)
                // Runtime は linux/arm64 だけ。ビルドする端末が amd64 でも arm64 のイメージを作る
                .agentRuntimeArtifact(AgentRuntimeArtifact.fromAsset(dockerContext, DockerImageAssetOptions.builder()
                        .platform(Platform.LINUX_ARM64)
                        .build()))
                .protocolConfiguration(ProtocolType.HTTP)
                // Bedrock と Nominatim（Gateway の先の Lambda）に出るので、パブリックネットワークを使う
                .networkConfiguration(RuntimeNetworkConfiguration.usingPublicNetwork())
                .authorizerConfiguration(RuntimeAuthorizerConfiguration.usingJWT(discoveryUrl, null, audience))
                .environmentVariables(env)
                // Runtime は JWT を検証したあと、デフォルトでは Authorization をコンテナに渡さない。
                // 渡さないと、Gateway に送るトークンと、メモリの actorId（JWT の sub）が取れない
                .requestHeaderConfiguration(RequestHeaderConfiguration.builder()
                        .allowlistedHeaders(List.of("Authorization"))
                        .build())
                // X-Ray のトレースと、アプリのログ・使用量のログ（CloudWatch Logs）
                .tracingEnabled(true)
                .loggingConfigs(List.of(
                        LoggingConfig.builder()
                                .logType(LogType.APPLICATION_LOGS)
                                .destination(LoggingDestination.cloudWatchLogs(logs))
                                .build(),
                        LoggingConfig.builder()
                                .logType(LogType.USAGE_LOGS)
                                .destination(LoggingDestination.cloudWatchLogs(logs))
                                .build()))
                .build());

        runtime.getRole().addToPrincipalPolicy(bedrockInvokePolicy(modelId));
        // Memory の読み書きは、このスタックで作った Memory だけに絞る
        memory.grantRead(runtime.getRole());
        memory.grantWrite(runtime.getRole());
        // grantRead には GetMemory が入っていない（synth した結果で確かめた）。Spring AI 版が長期メモリの戦略を
        // AWS から読む（auto-discovery）のに要るので、同じ Memory に絞って足す
        runtime.getRole().addToPrincipalPolicy(PolicyStatement.Builder.create()
                .effect(Effect.ALLOW)
                .actions(List.of("bedrock-agentcore:GetMemory"))
                .resources(List.of(memory.getMemoryArn()))
                .build());
        return runtime;
    }

    /**
     * Bedrock の呼び出しの権限。推論プロファイルと、その振り分け先の基盤モデル（東京と大阪）の両方を許可する。
     *
     * <p>基盤モデルの ID は、推論プロファイルの ID から {@code jp.} を外したものとみなしている。
     */
    private PolicyStatement bedrockInvokePolicy(String modelId) {
        String foundationModelId = modelId.substring(modelId.indexOf('.') + 1);
        List<String> resources = new java.util.ArrayList<>();
        resources.add("arn:aws:bedrock:" + getRegion() + ":" + getAccount() + ":inference-profile/" + modelId);
        for (String region : JP_REGIONS) {
            resources.add("arn:aws:bedrock:" + region + "::foundation-model/" + foundationModelId);
        }
        return PolicyStatement.Builder.create()
                .effect(Effect.ALLOW)
                .actions(List.of("bedrock:InvokeModel", "bedrock:InvokeModelWithResponseStream"))
                .resources(resources)
                .build();
    }

    /**
     * ツールの形。Lambda（{@code gateway/geocoding/handler.py}）の入出力と揃える。
     *
     * <p>出力は地名をキーにした Map なので、properties で並べられない。形は description で伝える。
     */
    static ToolDefinition toolDefinition() {
        return ToolDefinition.builder()
                .name(TOOL_NAME)
                .description("複数の地名・施設名から、座標（緯度・経度）と住所をまとめて調べる。日本国内が対象。"
                        + "座標は必ずこのツールの結果を使うこと。調べたい候補は 1 回でまとめて渡すこと。")
                .inputSchema(SchemaDefinition.builder()
                        .type(SchemaDefinitionType.OBJECT)
                        .properties(Map.of("queries", SchemaDefinition.builder()
                                .type(SchemaDefinitionType.ARRAY)
                                .description("地名・施設名のリスト（例: [\"代々木公園\", \"昭和記念公園\"]）")
                                .items(SchemaDefinition.builder()
                                        .type(SchemaDefinitionType.STRING)
                                        .description("地名・施設名")
                                        .build())
                                .build()))
                        .required(List.of("queries"))
                        .build())
                .outputSchema(SchemaDefinition.builder()
                        .type(SchemaDefinitionType.OBJECT)
                        .description("地名をキー、候補のリストを値とするオブジェクト。候補は name（施設名）/ displayName（住所）/ "
                                + "lat（緯度）/ lng（経度）を持つ。見つからなかった地名は空のリストになる。")
                        .build())
                .build();
    }

    /**
     * Nominatim を呼ぶ Lambda。
     *
     * <p>同時実行数を 1 にしている。Nominatim の利用規約は 1 秒に 1 回が目安で、Lambda の中の間隔の制御は 1 つのプロセスの中でしか効かないため。
     */
    private Function geocodingFunction() {
        String name = "cnj-agentcore-lab-geocoding";
        LogGroup logs = LogGroup.Builder.create(this, "GeocodingLogs")
                .logGroupName("/aws/lambda/" + name)
                .retention(RetentionDays.ONE_WEEK)
                .removalPolicy(RemovalPolicy.DESTROY)
                .build();
        return Function.Builder.create(this, "Geocoding")
                .functionName(name)
                .description("Gateway のツール：Nominatim で地名から座標を引く")
                .runtime(software.amazon.awscdk.services.lambda.Runtime.PYTHON_3_13)
                .handler("handler.handler")
                .code(Code.fromAsset("../gateway/geocoding", software.amazon.awscdk.services.s3.assets.AssetOptions.builder()
                        .exclude(List.of("test_*.py", "__pycache__"))
                        .build()))
                .memorySize(256)
                .timeout(Duration.seconds(60))
                .reservedConcurrentExecutions(1)
                .tracing(Tracing.ACTIVE)
                .logGroup(logs)
                .build();
    }

    private void output(String id, String value) {
        CfnOutput.Builder.create(this, id).value(value).build();
    }

    private String contextString(String key, String fallback) {
        Object v = getNode().tryGetContext(key);
        return v instanceof String s && !s.isBlank() ? s : fallback;
    }
}
