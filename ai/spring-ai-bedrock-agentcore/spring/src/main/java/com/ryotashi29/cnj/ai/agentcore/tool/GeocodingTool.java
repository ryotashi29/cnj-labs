package com.ryotashi29.cnj.ai.agentcore.tool;

import java.util.List;
import java.util.Map;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

/**
 * LLM に公開するツール。地名・施設名から座標を引く。中身は Gateway のツールを呼ぶだけ。
 *
 * <p>座標は必ずこのツールから取り、LLM には作らせない。呼べる回数に上限を設け、LLM を呼ぶ回数が増えすぎないようにする。
 * 呼んだ回数と利用者のトークンを持つので、リクエストごとに作る（Bean として共有しない）。
 */
public class GeocodingTool {

    static final int MAX_CALLS = 2;

    private final GatewayClient gateway;
    private final String authorization;
    private int calls;

    public GeocodingTool(GatewayClient gateway, String authorization) {
        this.gateway = gateway;
        this.authorization = authorization;
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
        if (queries == null || queries.isEmpty()) {
            return Map.of();
        }
        return gateway.resolve(queries, authorization);
    }
}
