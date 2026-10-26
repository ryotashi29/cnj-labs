package com.ryotashi29.cnj.ai.agentcore.agent;

import com.ryotashi29.cnj.ai.agentcore.tool.GatewayClient;
import com.ryotashi29.cnj.ai.agentcore.tool.GeocodingTool;
import java.util.List;
import org.springaicommunity.agentcore.memory.longterm.AgentCoreMemory;
import org.springframework.ai.chat.client.AdvisorParams;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.client.advisor.api.Advisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

/**
 * 犬連れのお出かけ先を提案するエージェント（Spring AI 版）。
 *
 * <p>{@code ai/spring-ai-bedrock-agent} と同じエージェントを、AgentCore にデプロイする形にしたもの。違いは 2 つ。
 *
 * <ul>
 *   <li>メモリ：{@code agentcore.memory.memory-id} があれば、SDK が AgentCore Memory を使う advisor（短期 + 長期）を用意する。
 *       なければ（手元の実行）、Spring AI のデフォルトの {@link ChatMemory}（JVM のヒープ）を使う
 *   <li>ツール：地名の検索を、プロセスの中ではなく AgentCore Gateway のツールとして呼ぶ
 * </ul>
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
    private final GatewayClient gateway;

    public OutingAgent(
            ChatClient.Builder builder,
            ObjectProvider<AgentCoreMemory> agentCoreMemory,
            ChatMemory chatMemory,
            GatewayClient gateway) {
        AgentCoreMemory memory = agentCoreMemory.getIfAvailable();
        List<Advisor> advisors = memory != null
                ? memory.advisors
                : List.of(MessageChatMemoryAdvisor.builder(chatMemory).build());
        this.chatClient = builder.defaultSystem(SYSTEM_PROMPT).defaultAdvisors(advisors).build();
        this.gateway = gateway;
    }

    /**
     * 1 回の会話。
     *
     * @param conversationId {@code "<actorId>:<sessionId>"}（{@link ConversationIds}）
     * @param authorization 呼び出した利用者の {@code Authorization}。Gateway にそのまま渡す
     */
    public AgentAnswer chat(String conversationId, String message, String authorization) {
        return chatClient
                .prompt()
                .user(message)
                .advisors(a -> a.param(ChatMemory.CONVERSATION_ID, conversationId))
                .advisors(AdvisorParams.ENABLE_NATIVE_STRUCTURED_OUTPUT)
                .tools(new GeocodingTool(gateway, authorization))
                .call()
                .entity(AgentAnswer.class);
    }
}
