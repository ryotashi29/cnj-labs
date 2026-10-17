package com.ryotashi29.cnj.ai.bedrockagent.agent;

import com.ryotashi29.cnj.ai.bedrockagent.tool.GeocodingClient;
import com.ryotashi29.cnj.ai.bedrockagent.tool.GeocodingTool;
import org.springframework.ai.chat.client.AdvisorParams;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.stereotype.Service;

/**
 * 犬連れのお出かけ先を提案するエージェント。
 *
 * <p>Spring AI の {@link ChatClient} で Bedrock（Converse API）を直接呼ぶ。エージェントとしての部品は 3 つだけ。
 *
 * <ul>
 *   <li>LLM：Bedrock の Claude（{@code application.yml} でモデルを指定）
 *   <li>ツール：{@link GeocodingTool}（地名から座標を引く）
 *   <li>メモリ：{@link ChatMemory}（Spring AI の自動構成。デフォルトは JVM のヒープに持つ）
 * </ul>
 *
 * <p>メモリは JVM のヒープにしかないので、再起動すると消え、2 台にすると共有されない。会話 ID は呼び出し側が渡すだけで、
 * 誰の会話かは確かめていない。どれも、本番で足りないものとして README で確かめる。
 */
@Service
public class OutingAgent {

    private static final String SYSTEM_PROMPT = """
            あなたは、犬連れの家族にお出かけ先を提案するアシスタントです。
            ユーザーの条件（地域・犬と一緒に入れるか・屋外か屋内か・目的など）を汲み取り、具体的な施設名を提案します。

            手順:
            1. 候補を 4 件考え、resolve_place_coordinates にまとめて渡す。
            2. ツールが返した座標と住所を使って回答する。座標が取れなかった候補は外し、3 件まで選ぶ。

            制約:
            - 緯度・経度は自分で作らず、必ずツールが返した値を使う。
            - 犬と一緒に入れるかが不確かな場合は、理由にそう書く。断定しない。
            - それまでの会話の条件（地域など）は引き継ぐ。
            """;

    private final ChatClient chatClient;
    private final GeocodingClient geocodingClient;

    public OutingAgent(ChatClient.Builder builder, ChatMemory chatMemory, GeocodingClient geocodingClient) {
        this.chatClient = builder
                .defaultSystem(SYSTEM_PROMPT)
                .defaultAdvisors(MessageChatMemoryAdvisor.builder(chatMemory).build())
                .build();
        this.geocodingClient = geocodingClient;
    }

    public AgentAnswer chat(String conversationId, String message) {
        return chatClient
                .prompt()
                .user(message)
                .advisors(a -> a.param(ChatMemory.CONVERSATION_ID, conversationId))
                // 回答の形（JSON スキーマ）を Bedrock 側で守らせる。これがないとスキーマはプロンプトで指示するだけになり、
                // 雑談のような質問で LLM が JSON ではなく普通の文章で答えると、JSON として読めずに失敗する
                .advisors(AdvisorParams.ENABLE_NATIVE_STRUCTURED_OUTPUT)
                // ツールは呼んだ回数を持つので、リクエストごとに作る
                .tools(new GeocodingTool(geocodingClient))
                .call()
                .entity(AgentAnswer.class);
    }
}
