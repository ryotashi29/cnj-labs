package com.ryotashi29.cnj.runtime.concurrency.webflux;

import com.ryotashi29.cnj.runtime.concurrency.common.config.StarvationProperties;
import com.ryotashi29.cnj.runtime.concurrency.common.config.WorkloadProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

/**
 * WebFlux 版。条件 webflux-r2dbc（{@code /api/db}）と webflux-blocking（{@code /api/blocking}）を
 * この 1 つの jar で切り替える。
 *
 * <p>R2DBC と JDBC の両方を持っているのは、{@code blocking} 条件のためにブロッキングドライバが
 * 必要だから。プールサイズは同じ {@code POOL_SIZE} から取るので、
 * 「接続数は同じでスタックだけ違う」比較になる。
 */
@SpringBootApplication
@EnableConfigurationProperties({WorkloadProperties.class, StarvationProperties.class})
public class Application {

    public static void main(String[] args) {
        SpringApplication.run(Application.class, args);
    }

}
