package com.ryotashi29.cnj.ai.agentcore.tool;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class GeocodingToolTest {

    @Test
    void 利用者のトークンを_Gateway_に渡し_上限を超えると呼ばない() {
        GatewayClient gateway = mock(GatewayClient.class);
        when(gateway.resolve(anyList(), eq("Bearer t"))).thenReturn(Map.of("代々木公園", List.of()));
        GeocodingTool tool = new GeocodingTool(gateway, "Bearer t");

        for (int i = 0; i < GeocodingTool.MAX_CALLS; i++) {
            assertThat(tool.resolvePlaceCoordinates(List.of("代々木公園"))).containsKey("代々木公園");
        }
        assertThat(tool.resolvePlaceCoordinates(List.of("代々木公園"))).containsKey("error");
        verify(gateway, org.mockito.Mockito.times(GeocodingTool.MAX_CALLS)).resolve(anyList(), eq("Bearer t"));
    }
}
