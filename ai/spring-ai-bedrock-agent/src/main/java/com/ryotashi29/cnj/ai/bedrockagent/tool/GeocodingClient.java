package com.ryotashi29.cnj.ai.bedrockagent.tool;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * OpenStreetMap の Nominatim で、地名から座標を引く。
 *
 * <p>Nominatim の利用規約に従い、識別できる User-Agent を付け、呼び出しは 1 秒に 1 回までにする（{@link GeocodingTool}
 * が直列に呼ぶ）。失敗したときは例外を投げず、空のリストを返す。
 */
@Component
public class GeocodingClient {

    private static final Logger log = LoggerFactory.getLogger(GeocodingClient.class);

    private final RestClient restClient;

    public GeocodingClient(
            RestClient.Builder builder,
            @Value("${app.nominatim.base-url}") String baseUrl,
            @Value("${app.nominatim.user-agent}") String userAgent) {
        this.restClient = builder.baseUrl(baseUrl).defaultHeader("User-Agent", userAgent).build();
    }

    public List<PlaceCandidate> search(String query, int limit) {
        if (query == null || query.isBlank()) {
            return List.of();
        }
        try {
            List<Map<String, Object>> items = restClient
                    .get()
                    .uri(uri -> uri.path("/search")
                            .queryParam("format", "jsonv2")
                            .queryParam("q", query.strip())
                            .queryParam("accept-language", "ja")
                            .queryParam("countrycodes", "jp")
                            .queryParam("limit", limit)
                            .build())
                    .accept(MediaType.APPLICATION_JSON)
                    .retrieve()
                    .body(new ParameterizedTypeReference<List<Map<String, Object>>>() {});
            return items == null ? List.of() : toCandidates(items);
        } catch (RuntimeException e) {
            log.warn("Nominatim の検索に失敗しました: query={}", query, e);
            return List.of();
        }
    }

    static List<PlaceCandidate> toCandidates(List<Map<String, Object>> items) {
        List<PlaceCandidate> candidates = new ArrayList<>();
        for (Map<String, Object> item : items) {
            String displayName = String.valueOf(item.getOrDefault("display_name", ""));
            String name = String.valueOf(item.getOrDefault("name", ""));
            if (name.isBlank()) {
                // 名前が空なら、住所の先頭（施設名であることが多い）を使う
                name = displayName.split(",")[0].strip();
            }
            try {
                double lat = Double.parseDouble(String.valueOf(item.get("lat")));
                double lng = Double.parseDouble(String.valueOf(item.get("lon")));
                candidates.add(new PlaceCandidate(name, displayName, lat, lng));
            } catch (NumberFormatException e) {
                // 座標がない候補は使わない
            }
        }
        return candidates;
    }
}
