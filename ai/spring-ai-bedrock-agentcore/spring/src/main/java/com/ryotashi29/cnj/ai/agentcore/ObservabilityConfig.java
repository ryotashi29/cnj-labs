package com.ryotashi29.cnj.ai.agentcore;

import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.OpenTelemetry;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Spring AI の観測データを、ADOT の Java エージェントの span につなぐ。
 *
 * <p>ADOT の Java エージェントは、AWS SDK や HTTP の呼び出しは自動で span にするが、Spring AI が Micrometer で出す
 * 観測データ（ChatClient の 1 回の呼び出し、ツールの実行など）は span にしない。そこで Micrometer Tracing の
 * OpenTelemetry のブリッジを入れ、Spring Boot が使う {@link OpenTelemetry} を、エージェントが用意したもの
 * （{@link GlobalOpenTelemetry}）にする。こうすると、Spring Boot は自前の SDK を作らず、span はエージェントが送る。
 *
 * <p>エージェントなしで起動したとき（手元の実行）は、何もしない OpenTelemetry になる。
 */
@Configuration(proxyBeanMethods = false)
class ObservabilityConfig {

    @Bean
    OpenTelemetry openTelemetry() {
        return GlobalOpenTelemetry.get();
    }
}
