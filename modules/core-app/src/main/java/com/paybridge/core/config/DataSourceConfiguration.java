package com.paybridge.core.config;

import com.paybridge.adapter.mysql.MysqlAdapterConfiguration;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The writer pool (env PAYBRIDGE_DATASOURCE_WRITER_*). The migrate role is given the pb_migrate credentials through the
 * same variables by the chart; every other role gets pb_core. Every connection runs in UTC (DMR section 2).
 */
@Configuration(proxyBeanMethods = false)
@Import(MysqlAdapterConfiguration.class)
public class DataSourceConfiguration {

    @Bean(destroyMethod = "close")
    DataSource writerDataSource(@Value("${paybridge.datasource.writer.url}") String url,
                                @Value("${paybridge.datasource.writer.username}") String username,
                                @Value("${paybridge.datasource.writer.password}") String password,
                                @Value("${paybridge.datasource.writer.pool.max-size:8}") int maxPoolSize) {
        var config = new HikariConfig();
        config.setJdbcUrl(url);
        config.setUsername(username);
        config.setPassword(password);
        config.setMaximumPoolSize(maxPoolSize);
        config.setPoolName("writer");
        config.setConnectionInitSql("SET time_zone = '+00:00'");
        return new HikariDataSource(config);
    }

    @Bean
    JdbcTemplate jdbcTemplate(DataSource writerDataSource) {
        return new JdbcTemplate(writerDataSource);
    }
}
