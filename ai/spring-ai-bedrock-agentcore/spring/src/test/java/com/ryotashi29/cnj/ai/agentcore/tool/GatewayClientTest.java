package com.ryotashi29.cnj.ai.agentcore.tool;

import static org.assertj.core.api.Assertions.assertThat;

import io.modelcontextprotocol.spec.McpSchema;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class GatewayClientTest {

    @Test
    void structuredContent_の_result_に包まれた値も取り出せる() {
        McpSchema.CallToolResult result = McpSchema.CallToolResult.builder()
                .structuredContent(Map.of("result", Map.of("代々木公園", List.of())))
                .build();
        assertThat(GatewayClient.payload(result)).containsKey("代々木公園");
    }

    @Test
    void テキストの_JSON_からも取り出せる() {
        McpSchema.CallToolResult result = McpSchema.CallToolResult.builder()
                .content(List.of(new McpSchema.TextContent("{\"代々木公園\": []}")))
                .build();
        assertThat(GatewayClient.payload(result)).containsKey("代々木公園");
    }

    @Test
    void URL_がなければ呼ばずに空を返す() {
        GatewayClient client = new GatewayClient("", "geocoding", "resolve_place_coordinates");
        assertThat(client.resolve(List.of("代々木公園"), "Bearer t")).isEmpty();
    }
}
