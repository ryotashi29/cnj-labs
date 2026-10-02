package com.ryotashi29.cnj.runtime.concurrency.webflux.config;

import com.zaxxer.hikari.HikariDataSource;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * {@code webflux-blocking} 条件のためのブロッキング DataSource。
 *
 * <p>Spring Boot は <b>{@code ConnectionFactory} Bean があると {@code DataSourceAutoConfiguration} を
 * 意図的に無効化する</b>（{@code @ConditionalOnMissingBean(type = "io.r2dbc.spi.ConnectionFactory")}）。
 * R2DBC を使うアプリが JDBC のプールまで抱えるのを防ぐための親切な既定だが、
 * この検証では「R2DBC とブロッキング JDBC を同じ接続数で並べる」ことが目的なので、
 * 自動設定に頼らず自分で組み立てる。
 *
 * <p>プールサイズは {@code spring.datasource.hikari.maximum-pool-size} 経由で
 * r2dbc-pool と同じ {@code POOL_SIZE} を読む。<b>ここが揃っていないと、測っているのは
 * 並行モデルの差ではなく接続数の差になる。</b>
 */
@Configuration
public class BlockingDataSourceConfig {

    /**
     * 接続情報は {@code @Value} で直接読む。{@code DataSourceProperties} を経由しないのは、
     * 自動設定が無効でもその Bean 自体は登録済みで、自分で定義すると候補が 2 つになって起動に失敗するため。
     * プールの設定値は生成後に {@code spring.datasource.hikari.*} から束縛される。
     */
    @Bean
    @ConfigurationProperties("spring.datasource.hikari")
    public HikariDataSource blockingDataSource(
            @Value("${spring.datasource.url}") String url,
            @Value("${spring.datasource.username}") String username,
            @Value("${spring.datasource.password}") String password,
            @Value("${spring.datasource.driverClassName}") String driverClassName) {
        HikariDataSource dataSource = new HikariDataSource();
        dataSource.setJdbcUrl(url);
        dataSource.setUsername(username);
        dataSource.setPassword(password);
        dataSource.setDriverClassName(driverClassName);
        return dataSource;
    }

    /**
     * 自動設定された {@code JdbcTemplate} は使えない（DataSource の自動設定ごと無効なため）。
     * 上の DataSource に明示的に紐付ける。
     */
    @Bean
    public JdbcTemplate blockingJdbcTemplate(DataSource dataSource) {
        return new JdbcTemplate(dataSource);
    }
}
