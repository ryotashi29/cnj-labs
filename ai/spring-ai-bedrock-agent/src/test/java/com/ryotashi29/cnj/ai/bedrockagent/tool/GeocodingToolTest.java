package com.ryotashi29.cnj.ai.bedrockagent.tool;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import org.junit.jupiter.api.Test;

class GeocodingToolTest {

    @Test
    void 上限の回数を超えると検索せずにエラーを返す() {
        GeocodingClient client = mock(GeocodingClient.class);
        when(client.search(anyString(), anyInt())).thenReturn(List.of());
        GeocodingTool tool = new GeocodingTool(client);

        for (int i = 0; i < GeocodingTool.MAX_CALLS; i++) {
            assertThat(tool.resolvePlaceCoordinates(List.of("代々木公園"))).containsKey("代々木公園");
        }
        assertThat(tool.resolvePlaceCoordinates(List.of("代々木公園"))).containsKey("error");
    }
}
