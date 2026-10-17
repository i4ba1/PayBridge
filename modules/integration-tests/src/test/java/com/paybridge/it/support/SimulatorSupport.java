package com.paybridge.it.support;

import com.paybridge.simulator.SimulatorApplication;
import java.net.URI;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * Starts the real simulator application in this JVM against the MySQL container: its own schema paybridge_sim, its own
 * account pb_sim (which has no rights on the PayBridge schema), a random port and short worker intervals.
 */
public final class SimulatorSupport {
    public static final String SCHEMA = "paybridge_sim";
    public static final String USER = "pb_sim";
    public static final String TOKEN = "sim-test-token-0123456789";
    public static final String WEBHOOK_SECRET = "whsec_test_0123456789abcdef";

    private SimulatorSupport() {}

    public record Running(ConfigurableApplicationContext context, URI baseUri) implements AutoCloseable {
        public String property(String name) {
            return context.getEnvironment().getProperty(name);
        }

        @Override
        public void close() {
            context.close();
        }
    }

    /** Drops and recreates paybridge_sim and the pb_sim account. The core's pb_core account is untouched and gets nothing. */
    public static void resetDatabase() {
        try (var ds = (com.zaxxer.hikari.HikariDataSource) MysqlSupport.rootDataSource(); var con = ds.getConnection();
             var st = con.createStatement()) {
            st.execute("DROP DATABASE IF EXISTS " + SCHEMA);
            st.execute("CREATE DATABASE " + SCHEMA + " CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci");
            st.execute("CREATE USER IF NOT EXISTS '" + USER + "'@'%' IDENTIFIED BY '" + MysqlSupport.PASSWORD + "'");
            st.execute("GRANT ALL PRIVILEGES ON " + SCHEMA + ".* TO '" + USER + "'@'%'");
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    public static Running start(String webhookTargetUrl, String... extraArgs) {
        var args = new ArrayList<>(List.of(
                "--server.port=0",
                "--management.server.port=-1",
                "--paybridge.simulator.datasource.url=" + MysqlSupport.jdbcUrl().replace("/" + MysqlSupport.SCHEMA + "?", "/" + SCHEMA + "?"),
                "--paybridge.simulator.datasource.username=" + USER,
                "--paybridge.simulator.datasource.password=" + MysqlSupport.PASSWORD,
                "--paybridge.simulator.bearer-token=" + TOKEN,
                "--paybridge.simulator.webhook.target-url=" + webhookTargetUrl,
                "--paybridge.simulator.webhook.secret=" + WEBHOOK_SECRET,
                "--paybridge.simulator.webhook.default-delay-ms=50",
                "--paybridge.simulator.completion-interval-ms=200",
                "--paybridge.simulator.delivery-interval-ms=100",
                "--spring.main.banner-mode=off",
                "--spring.config.name=simulator",
                // The integration test classpath also carries the core's GraphQL, JPA and Redis stacks; the simulator has none.
                "--spring.autoconfigure.exclude=" + String.join(",", List.of(
                        "org.springframework.boot.autoconfigure.graphql.GraphQlAutoConfiguration",
                        "org.springframework.boot.autoconfigure.graphql.servlet.GraphQlWebMvcAutoConfiguration",
                        "org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration",
                        "org.springframework.boot.autoconfigure.data.jpa.JpaRepositoriesAutoConfiguration",
                        "org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration",
                        "org.springframework.boot.autoconfigure.data.redis.RedisRepositoriesAutoConfiguration",
                        "com.netflix.graphql.dgs.springgraphql.autoconfig.DgsSpringGraphQLAutoConfiguration",
                        "com.netflix.graphql.dgs.autoconfig.DgsAutoConfiguration"))));
        args.addAll(List.of(extraArgs));
        var ctx = new SpringApplicationBuilder(SimulatorApplication.class).web(WebApplicationType.SERVLET)
                .run(args.toArray(String[]::new));
        int port = Integer.parseInt(ctx.getEnvironment().getProperty("local.server.port"));
        return new Running(ctx, URI.create("http://127.0.0.1:" + port));
    }
}
