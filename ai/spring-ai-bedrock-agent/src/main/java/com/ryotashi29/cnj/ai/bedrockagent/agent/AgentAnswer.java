package com.ryotashi29.cnj.ai.bedrockagent.agent;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import java.util.List;

/** エージェントの回答。Spring AI の構造化出力で、LLM にこの形で返させる。 */
public record AgentAnswer(
        @JsonPropertyDescription("ユーザー向けの補足（1〜2 文）") String message,
        @JsonPropertyDescription("提案する場所（3 件まで）") List<SuggestedPlace> places) {

    public record SuggestedPlace(
            @JsonPropertyDescription("施設・スポットの名前") String name,
            @JsonPropertyDescription("住所（ツールが返した値）") String displayName,
            @JsonPropertyDescription("緯度（必ずツールが返した値を使う）") double lat,
            @JsonPropertyDescription("経度（必ずツールが返した値を使う）") double lng,
            @JsonPropertyDescription("薦める理由（1 文）") String reason) {}
}
