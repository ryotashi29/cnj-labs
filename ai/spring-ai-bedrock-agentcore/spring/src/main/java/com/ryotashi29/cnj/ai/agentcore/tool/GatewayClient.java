package com.ryotashi29.cnj.ai.agentcore.tool;

import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.json.TypeRef;
import io.modelcontextprotocol.spec.McpSchema;
import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * AgentCore Gateway（MCP）のツールを呼ぶ。
 *
 * <p>Gateway の Lambda ターゲット（{@code gateway/geocoding/handler.py}）が地名から座標を引く。Strands 版も同じツールを使う。
 *
 * <p><b>なぜ {@code spring-ai-starter-mcp-client} の自動構成を使わないか</b>：starter は MCP クライアントを Bean
 * として 1 つ作るが、Gateway に渡すトークンはリクエストごとに違う（呼び出した利用者の ID トークン）。共有のクライアントに固定の
 * ヘッダとして載せられないので、MCP Java SDK を直接使い、リクエストごとにクライアントを作って閉じる。
 */
@Component
public class GatewayClient {

    private static final Logger log = LoggerFactory.getLogger(GatewayClient.class);

    /** Gateway は「ターゲット名___ツール名」の形でツールを公開する。 */
    private static final String NAME_SEPARATOR = "___";

    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(60);
    private static final Duration INITIALIZATION_TIMEOUT = Duration.ofSeconds(20);
    private static final TypeRef<Map<String, Object>> MAP_TYPE = new TypeRef<>() {};

    private final String gatewayUrl;
    private final String toolName;

    public GatewayClient(
            @Value("${app.gateway.url:}") String gatewayUrl,
            @Value("${app.gateway.target-name:geocoding}") String targetName,
            @Value("${app.gateway.tool-name:resolve_place_coordinates}") String toolName) {
        this.gatewayUrl = gatewayUrl == null ? "" : gatewayUrl.strip();
        this.toolName = targetName + NAME_SEPARATOR + toolName;
    }

    /**
     * 複数の地名の座標をまとめて引く。
     *
     * @param authorization 呼び出した利用者の {@code Authorization}（{@code Bearer <ID トークン>}）。そのまま Gateway に渡す
     * @return 地名をキー、候補のリストを値とする Map。失敗したときは空の Map（例外は投げない）
     */
    public Map<String, Object> resolve(List<String> queries, String authorization) {
        if (gatewayUrl.isBlank()) {
            log.warn("GATEWAY_URL が設定されていないので、ツールを呼べません");
            return Map.of();
        }
        try (ToolSession session = openSession(authorization)) {
            McpSchema.CallToolResult result = session.callTool(toolName, Map.of("queries", queries));
            if (result == null || Boolean.TRUE.equals(result.isError())) {
                log.warn("Gateway のツールの呼び出しが失敗しました: tool={}", toolName);
                return Map.of();
            }
            return payload(result);
        } catch (RuntimeException e) {
            log.warn("Gateway に接続できませんでした", e);
            return Map.of();
        }
    }

    /** Gateway への MCP セッションを開く。テストではここを差し替えて、通信をなくす。 */
    protected ToolSession openSession(String authorization) {
        HttpClientStreamableHttpTransport.Builder transport =
                HttpClientStreamableHttpTransport.builder(gatewayUrl).connectTimeout(INITIALIZATION_TIMEOUT);
        if (authorization != null && !authorization.isBlank()) {
            // 初期化・ツールの呼び出しのすべてに Authorization を付ける
            transport.httpRequestCustomizer(
                    (request, method, uri, body, context) -> request.header("Authorization", authorization));
        }
        McpSyncClient client = McpClient.sync(transport.build())
                .requestTimeout(REQUEST_TIMEOUT)
                .initializationTimeout(INITIALIZATION_TIMEOUT)
                .build();
        client.initialize();
        return new McpToolSession(client);
    }

    /**
     * ツールの結果から、地名ごとの候補を取り出す。
     *
     * <p>Lambda ターゲットの戻り値は {@code structuredContent} に入ることも、テキストの JSON で返ることもあるので、両方を受ける。
     */
    static Map<String, Object> payload(McpSchema.CallToolResult result) {
        if (result.structuredContent() instanceof Map<?, ?> structured) {
            Map<String, Object> map = cast(structured);
            // 値が object でないと {"result": ...} で包まれることがある
            return map.get("result") instanceof Map<?, ?> inner ? cast(inner) : map;
        }
        for (McpSchema.Content content : result.content() == null ? List.<McpSchema.Content>of() : result.content()) {
            if (content instanceof McpSchema.TextContent text && text.text() != null) {
                try {
                    return McpJsonDefaults.getMapper().readValue(text.text(), MAP_TYPE);
                } catch (IOException | RuntimeException e) {
                    // JSON でなければ次を見る
                }
            }
        }
        return Map.of();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> cast(Map<?, ?> map) {
        return (Map<String, Object>) map;
    }

    /** テストで差し替えるための窓（{@link McpSyncClient} は外から作れないため）。 */
    interface ToolSession extends AutoCloseable {

        McpSchema.CallToolResult callTool(String name, Map<String, Object> arguments);

        @Override
        void close();
    }

    private record McpToolSession(McpSyncClient client) implements ToolSession {

        @Override
        public McpSchema.CallToolResult callTool(String name, Map<String, Object> arguments) {
            return client.callTool(McpSchema.CallToolRequest.builder(name).arguments(arguments).build());
        }

        @Override
        public void close() {
            client.close();
        }
    }
}
