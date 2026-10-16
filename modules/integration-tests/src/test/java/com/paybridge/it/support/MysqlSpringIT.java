package com.paybridge.it.support;

import com.paybridge.adapter.mysql.MysqlAdapterConfiguration;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Base for tests that need the real stores. Each subclass gets a freshly migrated schema and its own Spring context
 * (the schema is dropped between classes, so a context must not outlive its class). The application connects as
 * pb_core, so every test also exercises the least privilege grants.
 */
@Tag("integration")
@SpringBootTest(classes = MysqlSpringIT.TestApp.class, properties = {
        "spring.flyway.enabled=false",
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.jpa.open-in-view=false",
        "spring.jpa.properties.hibernate.jdbc.time_zone=UTC",
        "spring.datasource.hikari.connection-init-sql=SET time_zone = '+00:00'"})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
public abstract class MysqlSpringIT {

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @Import(MysqlAdapterConfiguration.class)
    static class TestApp {}

    @BeforeAll
    static void migratedSchema() {
        MysqlSupport.freshlyMigratedSchema();
    }

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MysqlSupport::jdbcUrl);
        registry.add("spring.datasource.username", () -> MysqlSupport.CORE_USER);
        registry.add("spring.datasource.password", () -> MysqlSupport.PASSWORD);
    }
}
