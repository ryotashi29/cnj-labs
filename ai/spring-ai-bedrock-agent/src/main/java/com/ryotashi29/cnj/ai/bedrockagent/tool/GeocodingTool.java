package com.ryotashi29.cnj.ai.bedrockagent.tool;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

/**
 * LLM に公開するツール。地名・施設名から座標を引く。
 *
 * <p>座標は必ずこのツールから取り、LLM には作らせない。呼べる回数に上限を設け、LLM との往復が増えすぎないようにする。
 * 呼んだ回数を持つので、リクエストごとに作る（Bean として共有しない）。
 */
public class GeocodingTool {

    static final int MAX_CALLS = 2;
    private static final int CANDIDATES_PER_QUERY = 2;
    /** Nominatim の利用規約（1 秒に 1 回まで）に合わせた間隔。 */
    private static final long INTERVAL_MILLIS = 1_000;

    private final GeocodingClient client;
    private int calls;

    public GeocodingTool(GeocodingClient client) {
        this.client = client;
    }

    @Tool(
            name = "resolve_place_coordinates",
            description = "複数の地名・施設名から、座標（緯度・経度）と住所をまとめて調べる。日本国内が対象。"
                    + "座標は必ずこのツールの結果を使うこと。呼べる回数には上限があるので、調べたい候補は 1 回でまとめて渡すこと。")
    public Map<String, Object> resolvePlaceCoordinates(
            @ToolParam(description = "地名・施設名のリスト（例: [\"代々木公園\", \"昭和記念公園\"]）") List<String> queries) {
        if (calls >= MAX_CALLS) {
            return Map.of("error", "これ以上は検索できません。取得済みの候補だけで回答してください。");
        }
        calls++;
        Map<String, Object> results = new LinkedHashMap<>();
        for (String query : queries == null ? List.<String>of() : queries) {
            if (!results.isEmpty()) {
                sleep();
            }
            results.put(query, client.search(query, CANDIDATES_PER_QUERY));
        }
        return results;
    }

    private static void sleep() {
        try {
            Thread.sleep(INTERVAL_MILLIS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
