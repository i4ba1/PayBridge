package com.paybridge.adapter.mysql;

import java.time.Clock;
import javax.sql.DataSource;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

/** Import this from the composition root. Entities and repositories stay inside the adapter package. */
@Configuration(proxyBeanMethods = false)
@ComponentScan(basePackageClasses = MysqlAdapterConfiguration.class)
@EntityScan(basePackageClasses = MysqlAdapterConfiguration.class)
@EnableJpaRepositories(basePackageClasses = MysqlAdapterConfiguration.class, considerNestedRepositories = true)
public class MysqlAdapterConfiguration {

    @Bean
    Clock clock() {
        // DATETIME(6) stores microseconds; truncating here keeps what the application returns equal to what is stored.
        return Clock.tick(Clock.systemUTC(), java.time.Duration.ofNanos(1000));
    }

    @Bean
    SchemaVersionHealthIndicator schemaVersion(DataSource dataSource) {
        return new SchemaVersionHealthIndicator(dataSource);
    }
}
