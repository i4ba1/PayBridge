package com.paybridge.it.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.paybridge.application.port.IdentityStore;
import com.paybridge.application.port.IdentityStore.GlobalRole;
import com.paybridge.core.CoreApplication;
import com.zaxxer.hikari.HikariDataSource;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * The real core application over HTTP: MySQL and Redis in containers, the console's signing key replaced by a test key
 * whose public half is published through a JWKS file exactly as the Secret mount does. Each subclass gets a freshly
 * migrated schema and its own context. Every request is signed like the console would sign it.
 */
@Tag("integration")
@SpringBootTest(classes = CoreApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
public abstract class CoreHttpIT {
    public static final String PASSWORD = "Correct-horse-battery-1";
    protected static final ObjectMapper JSON = new ObjectMapper();
    protected static final TestKeys.Key KEY = TestKeys.generate("console-test-1");
    private static Path jwks;
    protected static HikariDataSource root;
    protected static JdbcTemplate rootJdbc;

    @LocalServerPort protected int port;
    @Autowired protected IdentityStore identity;
    @Autowired protected PasswordEncoder encoder;
    @Autowired protected StringRedisTemplate redis;
    protected final HttpClient http = HttpClient.newHttpClient();

    public record Response(int status, JsonNode body) {
        public JsonNode data() {
            return body.path("data");
        }
    }

    @BeforeAll
    static void schemaAndKeys() throws IOException {
        MysqlSupport.freshlyMigratedSchema();
        jwks = Files.createTempFile("jwks", ".json");
        TestKeys.writeJwks(jwks, List.of(KEY));
        root = (HikariDataSource) MysqlSupport.rootDataSource();
        rootJdbc = new JdbcTemplate(root);
    }

    @AfterAll
    static void closeRoot() {
        root.close();
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry r) {
        r.add("paybridge.datasource.writer.url", MysqlSupport::jdbcUrl);
        r.add("paybridge.datasource.writer.username", () -> MysqlSupport.CORE_USER);
        r.add("paybridge.datasource.writer.password", () -> MysqlSupport.PASSWORD);
        r.add("spring.data.redis.host", RedisSupport::host);
        r.add("spring.data.redis.port", () -> RedisSupport.port());
        r.add("spring.data.redis.timeout", () -> "1s");
        r.add("paybridge.security.service-context.jwks-file", () -> jwks.toString());
        r.add("paybridge.roles", () -> "api");
    }

    @BeforeEach
    void cleanState() {
        rootJdbc.execute("SET FOREIGN_KEY_CHECKS = 0");
        for (String t : List.of("audit_action", "membership", "app_user", "merchant", "invoice")) {
            rootJdbc.update("DELETE FROM " + t);
        }
        rootJdbc.execute("SET FOREIGN_KEY_CHECKS = 1");
        rootJdbc.update("DELETE FROM bootstrap_state");
        rootJdbc.update("INSERT INTO bootstrap_state (id, state, created_at) VALUES (1, 'PENDING', UTC_TIMESTAMP(6))");
        redis.execute((org.springframework.data.redis.core.RedisCallback<Object>) c -> {
            c.serverCommands().flushAll();
            return null;
        });
    }

    // ---- fixtures ----------------------------------------------------------------------------------------------

    protected String user(String username, GlobalRole role, boolean mustChange) {
        String id = Fixtures.uuid();
        identity.create(new IdentityStore.NewUser(id, username, username, encoder.encode(PASSWORD), role, mustChange));
        return id;
    }

    protected String admin(String username) {
        return user(username, GlobalRole.ADMIN, false);
    }

    protected String merchant(String code, String currency) {
        String id = Fixtures.uuid();
        rootJdbc.update("INSERT INTO merchant (id, code, name, currency, created_at, updated_at) "
                + "VALUES (?, ?, ?, ?, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))", id, code, "Merchant " + code, currency);
        return id;
    }

    protected void member(String userId, String merchantId, String role) {
        rootJdbc.update("INSERT INTO membership (id, user_id, merchant_id, role, created_at, updated_at) "
                + "VALUES (?, ?, ?, ?, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))", Fixtures.uuid(), userId, merchantId, role);
    }

    protected int auditCount(String action) {
        return rootJdbc.queryForObject("SELECT COUNT(*) FROM audit_action WHERE action = ?", Integer.class, action);
    }

    // ---- requests ----------------------------------------------------------------------------------------------

    protected String token(String sub, String mid, String op) {
        return TestKeys.sign(KEY, TestKeys.claims(sub, mid, op, Instant.now().getEpochSecond()));
    }

    /** Posts one operation signed for exactly that operation name. */
    protected Response call(String sub, String mid, String operation, String document, Map<String, Object> variables) {
        return post(token(sub, mid, operation), document, variables, operation, Map.of());
    }

    protected Response post(String bearer, String document, Map<String, Object> variables, String operationName,
                            Map<String, String> headers) {
        try {
            var body = new HashMap<String, Object>();
            body.put("query", document);
            body.put("operationName", operationName);
            body.put("variables", variables == null ? Map.of() : variables);
            var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/graphql"))
                    .header("Content-Type", "application/json").header("Accept", "application/json");
            if (bearer != null) {
                request.header("Authorization", "Bearer " + bearer);
            }
            headers.forEach(request::header);
            var response = http.send(request.POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body))).build(),
                    HttpResponse.BodyHandlers.ofString());
            String text = response.body();
            return new Response(response.statusCode(), text == null || text.isBlank() ? JSON.createObjectNode() : JSON.readTree(text));
        } catch (IOException | InterruptedException e) {
            throw new IllegalStateException(e);
        }
    }

    // ---- documents ---------------------------------------------------------------------------------------------

    protected static final String USER_FIELDS = "id username displayName admin enabled passwordChangeRequired "
            + "memberships { merchantId merchantName role }";
    protected static final String PROBLEM = "... on OperationProblem { code message retryAfterSeconds "
            + "fieldProblems { field code message } }";

    protected static final String AUTHENTICATE = "mutation Authenticate($input: AuthenticateInput!) { authenticate(input: $input) "
            + "{ __typename ... on Authenticated { user { " + USER_FIELDS + " } } " + PROBLEM + " } }";

    protected Response authenticate(String username, String password, String clientAddress) {
        return post(token(null, null, "Authenticate"), AUTHENTICATE,
                Map.of("input", Map.of("username", username, "password", password)), "Authenticate",
                Map.of("X-Client-Address", clientAddress));
    }
}
