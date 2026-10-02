package com.ryotashi29.cnj.runtime.concurrency.mvc;

import com.ryotashi29.cnj.runtime.concurrency.common.config.StarvationProperties;
import com.ryotashi29.cnj.runtime.concurrency.common.config.WorkloadProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

/**
 * Spring MVC 版。プラットフォームスレッドと仮想スレッドの 2 条件をこの 1 つの jar で切り替える。
 *
 * <p>{@code WorkloadProperties} は common モジュール（このクラスの基底パッケージの外）にあるため、
 * {@code @ConfigurationPropertiesScan} では拾えない。webflux 版と同じ設定クラスを共有していることを
 * 明示する意味も兼ねて、型を直接指定して登録している。
 */
@SpringBootApplication
@EnableConfigurationProperties({WorkloadProperties.class, StarvationProperties.class})
public class Application {

    public static void main(String[] args) {
        SpringApplication.run(Application.class, args);
    }

}
