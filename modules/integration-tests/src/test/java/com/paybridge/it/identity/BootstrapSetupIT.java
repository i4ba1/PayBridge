package com.paybridge.it.identity;

import static org.assertj.core.api.Assertions.assertThat;

import com.paybridge.it.support.CoreHttpIT;
import java.util.ArrayList;
import java.util.Map;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;

/** FR-001: first run setup succeeds once, and a reused, wrong or expired secret cannot create another administrator. */
class BootstrapSetupIT extends CoreHttpIT {
    private static final String TOKEN = "setup-secret-for-tests";
    private static final String SETUP_STATUS = "query SetupStatus { setupStatus { setupRequired } }";
    private static final String BOOTSTRAP = "mutation BootstrapAdministrator($input: BootstrapInput!) { bootstrapAdministrator(input: $input) "
            + "{ __typename ... on AdministratorCreated { user { " + USER_FIELDS + " } } " + PROBLEM + " } }";

    private void arm(int hoursValid) {
        rootJdbc.update("UPDATE bootstrap_state SET state = 'PENDING', token_hash = SHA2(?, 256), "
                + "token_expires_at = DATE_ADD(UTC_TIMESTAMP(6), INTERVAL ? HOUR) WHERE id = 1", TOKEN, hoursValid);
    }

    private Response setup(String token, String username, String password) {
        return post(token(null, null, "BootstrapAdministrator"), BOOTSTRAP,
                Map.of("input", Map.of("bootstrapToken", token, "username", username, "displayName", "Platform Admin",
                        "password", password)), "BootstrapAdministrator", Map.of());
    }

    private String code(Response r) {
        return r.data().path("bootstrapAdministrator").path("code").asText();
    }

    @Test
    void setupStatusIsAnonymousAndReflectsWhetherAnAdministratorExists() {
        var before = post(token(null, null, "SetupStatus"), SETUP_STATUS, null, "SetupStatus", Map.of());
        assertThat(before.status()).isEqualTo(200);
        assertThat(before.data().path("setupStatus").path("setupRequired").asBoolean()).isTrue();

        arm(1);
        assertThat(setup(TOKEN, "first.admin", "A-long-enough-password").data().path("bootstrapAdministrator")
                .path("__typename").asText()).isEqualTo("AdministratorCreated");

        var after = post(token(null, null, "SetupStatus"), SETUP_STATUS, null, "SetupStatus", Map.of());
        assertThat(after.data().path("setupStatus").path("setupRequired").asBoolean()).isFalse();
    }

    @Test
    void setupSucceedsOnceAndTheReplayIsRefused() {
        arm(1);
        var first = setup(TOKEN, "first.admin", "A-long-enough-password");
        var created = first.data().path("bootstrapAdministrator");
        assertThat(created.path("__typename").asText()).isEqualTo("AdministratorCreated");
        assertThat(created.path("user").path("admin").asBoolean()).isTrue();
        assertThat(created.path("user").path("passwordChangeRequired").asBoolean()).isFalse();
        assertThat(created.path("user").path("memberships")).isEmpty();

        var replay = setup(TOKEN, "second.admin", "Another-long-password");
        assertThat(code(replay)).isEqualTo("SETUP_ALREADY_COMPLETED");

        assertThat(rootJdbc.queryForObject("SELECT COUNT(*) FROM app_user WHERE global_role = 'ADMIN'", Integer.class)).isOne();
        var state = rootJdbc.queryForMap("SELECT state, token_hash, consumed_by_user_id FROM bootstrap_state WHERE id = 1");
        assertThat(state.get("state")).isEqualTo("CONSUMED");
        assertThat(state.get("token_hash")).as("the hash is cleared once consumed").isNull();
        assertThat(state.get("consumed_by_user_id")).isEqualTo(created.path("user").path("id").asText());
        assertThat(auditCount("ADMIN_BOOTSTRAPPED")).isOne();
    }

    @Test
    void theAdministratorCanSignInWithTheChosenPassword() {
        arm(1);
        setup(TOKEN, "first.admin", "A-long-enough-password");
        var r = authenticate("first.admin", "A-long-enough-password", "10.0.0.1");
        assertThat(r.data().path("authenticate").path("__typename").asText()).isEqualTo("Authenticated");
        assertThat(r.data().path("authenticate").path("user").path("admin").asBoolean()).isTrue();
    }

    @Test
    void aWrongTokenCreatesNothingAndLeavesSetupOpen() {
        arm(1);
        var wrong = setup("not-the-secret", "first.admin", "A-long-enough-password");
        assertThat(code(wrong)).isEqualTo("BOOTSTRAP_TOKEN_INVALID");
        assertThat(rootJdbc.queryForObject("SELECT COUNT(*) FROM app_user", Integer.class)).isZero();
        assertThat(rootJdbc.queryForObject("SELECT state FROM bootstrap_state WHERE id = 1", String.class)).isEqualTo("PENDING");
    }

    @Test
    void anExpiredTokenIsRefusedWithTheSameAnswerAndMarkedExpired() {
        arm(-1);
        var expired = setup(TOKEN, "first.admin", "A-long-enough-password");
        assertThat(code(expired)).isEqualTo("BOOTSTRAP_TOKEN_INVALID");
        assertThat(expired.data().path("bootstrapAdministrator").path("message").asText())
                .isEqualTo(setup("wrong", "x.y.z", "A-long-enough-password").data().path("bootstrapAdministrator").path("message").asText());
        assertThat(rootJdbc.queryForObject("SELECT state FROM bootstrap_state WHERE id = 1", String.class)).isEqualTo("EXPIRED");
        assertThat(rootJdbc.queryForObject("SELECT COUNT(*) FROM app_user", Integer.class)).isZero();

        // Re-arming works while no administrator exists, and then setup succeeds.
        arm(1);
        assertThat(setup(TOKEN, "first.admin", "A-long-enough-password").data().path("bootstrapAdministrator")
                .path("__typename").asText()).isEqualTo("AdministratorCreated");
    }

    @Test
    void aValidTokenWithAWeakPasswordReportsFieldProblemsAndKeepsSetupOpen() {
        arm(1);
        var weak = setup(TOKEN, "first.admin", "short");
        assertThat(code(weak)).isEqualTo("VALIDATION_ERROR");
        assertThat(weak.data().path("bootstrapAdministrator").path("fieldProblems").get(0).path("field").asText()).isEqualTo("password");
        var badName = setup(TOKEN, "Bad Name", "A-long-enough-password");
        assertThat(code(badName)).isEqualTo("VALIDATION_ERROR");
        assertThat(rootJdbc.queryForObject("SELECT state FROM bootstrap_state WHERE id = 1", String.class)).isEqualTo("PENDING");
        assertThat(setup(TOKEN, "first.admin", "A-long-enough-password").data().path("bootstrapAdministrator")
                .path("__typename").asText()).isEqualTo("AdministratorCreated");
    }

    @Test
    void concurrentSetupAttemptsWithOneTokenCreateExactlyOneAdministrator() throws Exception {
        arm(1);
        var pool = Executors.newFixedThreadPool(6);
        try {
            var futures = new ArrayList<java.util.concurrent.Future<String>>();
            for (int i = 0; i < 6; i++) {
                int n = i;
                futures.add(pool.submit(() -> setup(TOKEN, "admin" + n + ".user", "A-long-enough-password").data()
                        .path("bootstrapAdministrator").path("__typename").asText()));
            }
            int created = 0;
            for (var f : futures) {
                if ("AdministratorCreated".equals(f.get())) {
                    created++;
                }
            }
            assertThat(created).isOne();
        } finally {
            pool.shutdownNow();
        }
        assertThat(rootJdbc.queryForObject("SELECT COUNT(*) FROM app_user WHERE global_role = 'ADMIN'", Integer.class)).isOne();
        assertThat(auditCount("ADMIN_BOOTSTRAPPED")).isOne();
    }

    @Test
    void theSetupSecretNeverAppearsInTheAuditRows() {
        arm(1);
        setup(TOKEN, "first.admin", "A-long-enough-password");
        var rows = rootJdbc.queryForList("SELECT CONCAT_WS('|', action, target_type, target_id, correlation_id, details) FROM audit_action", String.class);
        assertThat(rows).isNotEmpty().noneMatch(r -> r.contains(TOKEN) || r.contains("A-long-enough-password"));
    }
}
