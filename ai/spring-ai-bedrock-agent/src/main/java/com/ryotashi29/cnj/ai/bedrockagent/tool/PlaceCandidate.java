package com.ryotashi29.cnj.ai.bedrockagent.tool;

/** 地名検索の候補 1 件。座標は Nominatim が返した値をそのまま持つ。 */
public record PlaceCandidate(String name, String displayName, double lat, double lng) {}
