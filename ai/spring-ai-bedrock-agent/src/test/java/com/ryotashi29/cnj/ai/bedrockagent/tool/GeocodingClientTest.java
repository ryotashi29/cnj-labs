package com.ryotashi29.cnj.ai.bedrockagent.tool;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class GeocodingClientTest {

    @Test
    void 座標のある候補だけを返し_名前が空なら住所の先頭を使う() {
        List<PlaceCandidate> candidates = GeocodingClient.toCandidates(List.of(
                Map.of("name", "代々木公園", "display_name", "代々木公園, 渋谷区, 東京都", "lat", "35.67", "lon", "139.69"),
                Map.of("name", "", "display_name", "昭和記念公園, 立川市, 東京都", "lat", "35.70", "lon", "139.40"),
                Map.of("name", "座標なし", "display_name", "どこか")));

        assertThat(candidates)
                .extracting(PlaceCandidate::name)
                .containsExactly("代々木公園", "昭和記念公園");
        assertThat(candidates.getFirst().lat()).isEqualTo(35.67);
    }
}
