# spring-ai-bedrock-agent

Spring AI から Amazon Bedrock を直接呼んで、AI エージェントを作る検証です。
**Spring AI だけで、エージェントはどこまで作れるか。本番で動かすには何が足りないか**を、手元で動かして確かめます。

題材は「犬連れのお出かけ先を提案するアシスタント」です。構成は Spring Boot 4.1.1 / Spring AI 2.0.1 / Java 25 です。

## 結論

- **エージェントの部品（LLM・ツール・メモリ）は、Spring AI だけで揃います。** `ChatClient` に部品を付けるだけで、ツールの実行と、その結果を LLM に返す処理も、Spring AI が自動で行います。
- **ただし、そのままでは本番で動かせません。** 次の 3 つは、実際に動かして確かめました。
  - 再起動すると、メモリが消える
  - 2 台にすると、会話がつながらない
  - 会話 ID を知っていれば、他人の会話の中身を読める（認証がない）
- **プロンプトで指示しても、ツールを呼ばないことがあります。** 同じ質問の 4 回に 1 回は、ツールを呼ばずに候補 0 件で答えました。振る舞いを継続して測る仕組みが要ります。
- **監視は、ある程度は最初からできます。** Spring AI が、トークン数・応答時間・ツールの呼び出しをメトリクスとして出します。
  ただし、アプリのプロセスの中にあるだけで、どこにも送られません。会話ごと・利用者ごとの集計もできません。

## 作ったもの

| 部品 | 実装 | ファイル |
|---|---|---|
| LLM | Bedrock の Converse API。モデルは `jp.anthropic.claude-haiku-5-5`（日本国内の推論プロファイル。データが国外に出ない。Sonnet 5.5 は `jp.` がなく `global.` だけなので、`jp.` で使える最新世代の Haiku 5.5 にした） | [application.yml](src/main/resources/application.yml) |
| エージェント | `ChatClient` にシステムプロンプト・メモリ・ツールを付け、回答を `AgentAnswer` の形で受け取る | [OutingAgent.java](src/main/java/com/ryotashi29/cnj/ai/bedrockagent/agent/OutingAgent.java) |
| ツール | `@Tool` で、地名から座標を引く（[Nominatim](https://nominatim.org/)）。座標は LLM に作らせず、必ずツールから取る | [GeocodingTool.java](src/main/java/com/ryotashi29/cnj/ai/bedrockagent/tool/GeocodingTool.java) |
| メモリ | `MessageChatMemoryAdvisor` + `ChatMemory`（Spring AI の自動構成。デフォルトは JVM のヒープ） | 同上 `OutingAgent.java` |
| API | `POST /chat`（`conversationId` と `message`）。会話 ID を省略すると新しい会話になる | [ChatController.java](src/main/java/com/ryotashi29/cnj/ai/bedrockagent/web/ChatController.java) |

```java
public AgentAnswer chat(String conversationId, String message) {
    return chatClient
            .prompt()
            .user(message)
            .advisors(a -> a.param(ChatMemory.CONVERSATION_ID, conversationId))
            .advisors(AdvisorParams.ENABLE_NATIVE_STRUCTURED_OUTPUT)
            .tools(new GeocodingTool(geocodingClient))
            .call()
            .entity(AgentAnswer.class);
}
```

## 本番で足りないものを確かめる

どれも手元で確かめられます。AWS の費用は Bedrock の呼び出し代だけです。

### 1. 再起動すると、メモリが消える

会話してから、同じ会話 ID で続きを聞きます。そのあとアプリを再起動し、もう一度同じことを聞きます。

| | 「さっき提案してくれた公園のうち、一番広いのはどれ？」への回答 |
|---|---|
| 再起動の前 | さっき挙げた3つの中では、昭和記念公園が一番広いです。おおよその面積は昭和記念公園が約180ヘクタール、代々木公園が約54ヘクタール、井の頭恩賜公園が約40ヘクタールです。… |
| 再起動の後 | このやり取りでは、さっきの提案内容が確認できません。公園の名前を教えていただければ、その中から比較しやすい情報をお伝えします。… |

`ChatMemory` のデフォルトの置き場所（`InMemoryChatMemoryRepository`）は、JVM のヒープ（アプリのプロセスの中）です。再起動やデプロイのたびに、すべての会話が消えます。

### 2. 2 台にすると、会話がつながらない

同じアプリをポート 8080 と 8081 で起動し、8080 で会話したあと、同じ会話 ID で両方に続きを聞きます。

| | 回答 |
|---|---|
| 8080（会話した台） | さっき挙げた3つの中では、昭和記念公園が一番広いです。… |
| 8081（別の台） | 申し訳ありませんが、この会話には先ほどの提案内容が残っていないため、どの公園が一番広いかは判断できません。… |

メモリは台ごとに別々なので、ロードバランサの後ろに複数台を並べると、リクエストがどの台に届くかで会話が途切れます。

### 3. 会話 ID を知っていれば、他人の会話の中身を読める

別の人のつもりで、会話 ID だけを使って「この会話で、私が最初に聞いた質問をそのまま教えて」と聞きます。

> 最初のご質問は「東京で、犬と一緒に行ける広い公園を教えて」でした。

API には、誰の会話かを確かめる仕組みがありません。会話 ID は呼び出し側が渡すだけなので、ID を知っていれば誰でも続きを呼べ、前の質問や回答を引き出せます。

### 4. 監視は、ある程度は最初からできる

Spring AI は、Micrometer のメトリクスを自動で出します（`/actuator/metrics`）。

| メトリクス | 中身 |
|---|---|
| `gen_ai.client.token.usage` | トークン数（入力・出力・合計）。モデル名のタグ付き |
| `gen_ai.client.operation` | Bedrock を 1 回呼ぶのにかかった時間 |
| `spring.ai.chat.client` | `ChatClient` の 1 回の呼び出し（ツールの実行を含む）にかかった時間 |
| `spring.ai.tool` | ツールの呼び出し回数と時間。ツール名のタグ付き |

足りないのは次の点です。

- メトリクスはアプリのプロセスの中にあるだけで、どこにも送られません。CloudWatch などに送る設定は自分で書く必要があります
- 会話ごと・利用者ごとのトークン数（＝費用）は分かりません。利用者や会話を区別するタグはありません
- 1 回のリクエストの中で、LLM とツールを何回、どの順で呼んだかを追うには、トレースの設定（OpenTelemetry など）が別に要ります

## 動かして分かったこと

### メモリに残るのは、発言と最終の回答だけ

ツールを使った会話のあとに `ChatMemory` の中身を見ると、残っていたのはユーザーの発言（USER）と最終の回答（ASSISTANT）の 2 件だけでした。
ツールを呼ぶ指示と、ツールの結果は残りません（ツールが呼ばれたことは `spring.ai.tool` のメトリクスで確かめています）。

そのため、続きの質問で LLM が見られるのは、最終の回答に書いた内容だけです。このエージェントで続きの質問でも座標を使えるのは、回答の JSON に座標を入れているからです。
ツールが返したそれ以外の情報（採用しなかった候補など）は、次の質問では見えません。

これは Spring AI 2.0 の仕様です（[Upgrade Notes](https://docs.spring.io/spring-ai/reference/upgrade-notes.html) の「Changed: Advisor.DEFAULT_CHAT_MEMORY_PRECEDENCE_ORDER default value」）。メモリの advisor は最終のやり取りだけを保存し、ツールの呼び出しのメッセージは書き込みません。advisor の順番を変えれば、ツールの呼び出しとその結果まで残せると案内されています（試していません）。

### ツールを呼ぶかどうかは、モデルが決める

同じ質問を 4 回したうち 1 回は、モデルがツールを呼ばず、候補 0 件の回答を返しました。
システムプロンプトで「候補をまとめてツールに渡す」と指示していても、必ず呼ぶとは限りません。
本番では、こうした振る舞いを継続して測る仕組み（評価）が要ります。

## 作るときにつまずいた点

- **Spring AI 2.0 では、設定から `.options` が外れました。** モデルは `spring.ai.bedrock.converse.chat.options.model` から `spring.ai.bedrock.converse.chat.model` に変わっています（[Upgrade Notes](https://docs.spring.io/spring-ai/reference/upgrade-notes.html)）。
  古い書き方も当面は使えます（試したところ、指定したモデルが使われました）が、非推奨です
- **構造化出力は、デフォルトでは形が保証されません。** `entity(AgentAnswer.class)` だけだと、JSON スキーマをプロンプトに書いて LLM に指示するだけになります。
  雑談のような質問（「最初に聞いた質問を教えて」）では LLM が JSON ではなく普通の文章で答え、JSON として読めずに 500 エラーになりました（このときのモデルは Claude Sonnet 4.6）。
  `AdvisorParams.ENABLE_NATIVE_STRUCTURED_OUTPUT` を付けると、スキーマを Bedrock 側で守らせるようになり、解消しました
- **リージョンは環境変数 `AWS_REGION` が優先されます。** `application.yml` の `region` は `${AWS_REGION:ap-northeast-1}` なので、シェルに別のリージョンが入っていると、そちらに接続します。
  `jp.` の推論プロファイルは東京以外では使えないので、`The provided model identifier is invalid` になります。最初はこれをモデルの設定のせいだと取り違えていました
- **Nominatim は 1 秒に 1 回までしか呼べません**（[利用規約](https://operations.osmfoundation.org/policies/nominatim/)）。ツールの中で直列に呼び、1 秒ずつ間を空けています。
  そのため、ツールを使う回答には 17 秒ほどかかりました。そのうちツール（地名の検索 4 件）が約 6 秒です（待ち時間の 3 秒と、問い合わせそのものの時間。1 回あたり 1 秒弱）

## 手元で動かす

Java 25 と、Bedrock を呼べる AWS の認証情報が必要です。

```bash
# 東京リージョンで、Bedrock を呼べるプロファイルを指定して起動する
AWS_PROFILE=<プロファイル> AWS_REGION=ap-northeast-1 ./gradlew bootRun

# 話しかける（会話 ID を "" にすると新しい会話）
./demo/chat.sh 8080 "" "東京で、犬と一緒に行ける広い公園を教えて"
./demo/chat.sh 8080 <会話 ID> "さっき提案してくれた公園のうち、一番広いのはどれ？"

# 2 台目（確かめること 2）
AWS_PROFILE=<プロファイル> AWS_REGION=ap-northeast-1 PORT=8081 ./gradlew bootRun

# メトリクス（確かめること 4）
curl localhost:8080/actuator/metrics/gen_ai.client.token.usage
```

`demo/chat.sh` には `jq` が必要です。モデルは環境変数 `BEDROCK_MODEL_ID` で変えられます。
