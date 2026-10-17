package com.paybridge.it.identity;

import static com.paybridge.it.support.Ops.*;
import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.ThrowableProxy;
import ch.qos.logback.core.read.ListAppender;
import com.paybridge.application.port.IdentityStore.GlobalRole;
import com.paybridge.it.support.CoreHttpIT;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

/**
 * Temporary credentials are returned once, force a change, and never reach a log line or an audit row.
 * A log capture at DEBUG on every logger stands in for "credentials never appear in logs" (plan T05 acceptance).
 */
class PasswordResetIT extends CoreHttpIT {
    private ListAppender<ILoggingEvent> logs;
    private Logger rootLogger;
    private Level previousLevel;

    @BeforeEach
    void captureLogs() {
        rootLogger = (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
        previousLevel = rootLogger.getLevel();
        rootLogger.setLevel(Level.DEBUG);
        logs = new ListAppender<>();
        logs.start();
        rootLogger.addAppender(logs);
    }

    @AfterEach
    void releaseLogs() {
        rootLogger.detachAppender(logs);
        rootLogger.setLevel(previousLevel);
    }

    private void assertNeverLogged(String... secrets) {
        for (ILoggingEvent e : List.copyOf(logs.list)) {
            String text = e.getFormattedMessage() + " " + (e.getThrowableProxy() == null ? ""
                    : ((ThrowableProxy) e.getThrowableProxy()).getThrowable().getMessage()) + " " + e.getArgumentArray();
            for (String secret : secrets) {
                assertThat(text).as("log line from %s", e.getLoggerName()).doesNotContain(secret);
            }
        }
    }

    private void assertNeverAudited(String... secrets) {
        var rows = rootJdbc.queryForList("SELECT CONCAT_WS('|', actor_user_id, merchant_id, action, target_type, target_id, "
                + "result, correlation_id, details) FROM audit_action", String.class);
        assertThat(rows).isNotEmpty();
        for (String secret : secrets) {
            assertThat(rows).noneMatch(r -> r.contains(secret));
        }
    }

    @Test
    void aResetReturnsATemporaryCredentialOnceAndForcesAChange() {
        String admin = admin("root.admin");
        String target = user("target.user", GlobalRole.USER, false);

        var reset = call(admin, null, "ResetUserPassword", RESET_PASSWORD, Map.of("id", target));
        var payload = reset.data().path("resetUserPassword");
        assertThat(payload.path("__typename").asText()).isEqualTo("PasswordReset");
        assertThat(payload.path("passwordChangeRequired").asBoolean()).isTrue();
        String temporary = payload.path("temporaryCredential").asText();
        assertThat(temporary).matches("[A-Za-z0-9]{20}");

        assertThat(authenticate("target.user", PASSWORD, "10.1.1.1").data().path("authenticate").path("code").asText())
                .as("the old password stops working").isEqualTo("UNAUTHENTICATED");
        var signIn = authenticate("target.user", temporary, "10.1.1.1");
        assertThat(signIn.data().path("authenticate").path("user").path("passwordChangeRequired").asBoolean()).isTrue();

        assertNeverLogged(temporary, PASSWORD);
        assertNeverAudited(temporary, PASSWORD);
        assertThat(auditCount("USER_PASSWORD_RESET")).isOne();
    }

    @Test
    void aUserWhoMustChangeThePasswordMayCallOnlyMeChangePasswordAndMyMerchants() {
        String admin = admin("root.admin");
        String target = user("target.user", GlobalRole.USER, false);
        String temporary = call(admin, null, "ResetUserPassword", RESET_PASSWORD, Map.of("id", target)).data()
                .path("resetUserPassword").path("temporaryCredential").asText();

        assertThat(call(target, null, "Me", ME, Map.of()).data().path("me").path("passwordChangeRequired").asBoolean()).isTrue();
        assertThat(call(target, null, "MyMerchants", MY_MERCHANTS, Map.of()).body().path("errors")).isEmpty();
        var blocked = call(target, null, "Invoices", INVOICES, Map.of());
        assertThat(blocked.data().path("invoices").path("code").asText()).isEqualTo("PASSWORD_CHANGE_REQUIRED");

        String newPassword = "My-new-password-2026";
        var changed = call(target, null, "ChangePassword", CHANGE_PASSWORD,
                Map.of("input", Map.of("currentPassword", temporary, "newPassword", newPassword)));
        assertThat(changed.data().path("changePassword").path("__typename").asText()).isEqualTo("PasswordChanged");

        assertThat(call(target, null, "Me", ME, Map.of()).data().path("me").path("passwordChangeRequired").asBoolean()).isFalse();
        assertThat(call(target, null, "Invoices", INVOICES, Map.of()).data().path("invoices").path("code").asText())
                .as("no longer blocked by the temporary credential").isNotEqualTo("PASSWORD_CHANGE_REQUIRED");
        assertThat(authenticate("target.user", temporary, "10.1.1.2").data().path("authenticate").path("code").asText())
                .isEqualTo("UNAUTHENTICATED");
        assertThat(authenticate("target.user", newPassword, "10.1.1.2").data().path("authenticate").path("__typename").asText())
                .isEqualTo("Authenticated");

        assertThat(auditCount("PASSWORD_CHANGED")).isOne();
        assertNeverLogged(temporary, newPassword);
        assertNeverAudited(temporary, newPassword);
    }

    @Test
    void changePasswordEnforcesTheCurrentPasswordAndThePolicy() {
        String target = user("target.user", GlobalRole.USER, false);
        var wrong = call(target, null, "ChangePassword", CHANGE_PASSWORD,
                Map.of("input", Map.of("currentPassword", "not-my-password", "newPassword", "Another-long-password-1")));
        assertThat(wrong.data().path("changePassword").path("code").asText()).isEqualTo("UNAUTHENTICATED");

        var weak = call(target, null, "ChangePassword", CHANGE_PASSWORD,
                Map.of("input", Map.of("currentPassword", PASSWORD, "newPassword", "short")));
        assertThat(weak.data().path("changePassword").path("code").asText()).isEqualTo("VALIDATION_ERROR");
        assertThat(weak.data().path("changePassword").path("fieldProblems").get(0).path("field").asText()).isEqualTo("newPassword");

        var same = call(target, null, "ChangePassword", CHANGE_PASSWORD,
                Map.of("input", Map.of("currentPassword", PASSWORD, "newPassword", PASSWORD)));
        assertThat(same.data().path("changePassword").path("code").asText()).isEqualTo("VALIDATION_ERROR");
        assertThat(auditCount("PASSWORD_CHANGED")).isZero();
    }

    @Test
    void resettingAnUnknownUserIsNotFoundAndWritesNoAudit() {
        String admin = admin("root.admin");
        var r = call(admin, null, "ResetUserPassword", RESET_PASSWORD, Map.of("id", java.util.UUID.randomUUID().toString()));
        assertThat(r.data().path("resetUserPassword").path("code").asText()).isEqualTo("NOT_FOUND");
        assertThat(auditCount("USER_PASSWORD_RESET")).isZero();
    }

    @Test
    void createdAccountCredentialsStayOutOfLogsAndAuditToo() {
        String admin = admin("root.admin");
        var created = call(admin, null, "CreateUser", CREATE_USER, Map.of("input", Map.of("username", "fresh.user")));
        String temporary = created.data().path("createUser").path("temporaryCredential").asText();
        assertThat(temporary).hasSize(20);
        assertNeverLogged(temporary);
        assertNeverAudited(temporary);
    }

    @Test
    void passwordsSentInRequestsNeverReachTheLogsEvenWhenRejected() {
        user("target.user", GlobalRole.USER, false);
        String guess = "Guess-that-is-wrong-1234";
        authenticate("target.user", guess, "10.2.2.2");
        post(token(null, null, "Authenticate"), "this is not graphql " + guess, Map.of(), "Authenticate", Map.of());
        assertNeverLogged(guess);
    }
}
