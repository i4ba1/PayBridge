package com.paybridge.simulator.config;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.time.Clock;
import javax.sql.DataSource;
import org.apache.catalina.Valve;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.embedded.tomcat.TomcatServletWebServerFactory;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(SimulatorProperties.class)
public class SimulatorConfiguration {

    /** pb_sim owns paybridge_sim; every connection runs in UTC. Flyway uses this same data source (spring.flyway.*). */
    @Bean(destroyMethod = "close")
    DataSource dataSource(SimulatorProperties props) {
        var db = props.getDatasource();
        var c = new HikariConfig();
        c.setJdbcUrl(db.getUrl());
        c.setUsername(db.getUsername());
        c.setPassword(db.getPassword());
        c.setMaximumPoolSize(db.getPool().getMaxSize());
        c.setPoolName("simulator");
        c.setConnectionInitSql("SET time_zone = '+00:00'");
        return new HikariDataSource(c);
    }

    @Bean
    JdbcTemplate jdbcTemplate(DataSource dataSource) {
        return new JdbcTemplate(dataSource);
    }

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }

    /** Lets a handler end a request by closing the connection without any response bytes. */
    @Bean
    WebServerFactoryCustomizer<TomcatServletWebServerFactory> connectionAbortCustomizer() {
        return factory -> factory.addContextValves((Valve) new ConnectionAbortValve());
    }
}
