package com.paybridge.it.identity;

import static com.paybridge.it.support.Ops.*;
import static org.assertj.core.api.Assertions.assertThat;

import com.paybridge.application.port.IdentityStore.GlobalRole;
import com.paybridge.it.support.CoreHttpIT;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** FR-005: disabling an account takes effect on its next protected operation, without waiting for a session to end. */
class DisabledUserIT extends CoreHttpIT {

    @Test
    void aDisabledUserIsRefusedOnTheNextProtectedCallWithTheSameToken() {
        String admin = admin("root.admin");
        String a = merchant("a", "IDR");
        String operator = user("operator.a", GlobalRole.USER, false);
        member(operator, a, "OPERATOR");

        var before = call(operator, a, "Me", ME, Map.of());
        assertThat(before.data().path("me").path("username").asText()).isEqualTo("operator.a");

        var off = call(admin, null, "UpdateUser", UPDATE_USER, Map.of("id", operator, "input", Map.of("enabled", false)));
        assertThat(off.data().path("updateUser").path("user").path("enabled").asBoolean()).isFalse();

        var after = call(operator, a, "Me", ME, Map.of());
        assertThat(after.status()).isEqualTo(200);
        assertThat(after.data().path("me").isNull()).as("me is nullable and not visible once disabled").isTrue();
        var merchants = call(operator, a, "MyMerchants", MY_MERCHANTS, Map.of());
        assertThat(merchants.body().path("errors")).as("non null list: denial is an error").isNotEmpty();
        var invoices = call(operator, a, "Invoices", INVOICES, Map.of());
        assertThat(invoices.data().path("invoices").path("code").asText()).isEqualTo("UNAUTHENTICATED");
        assertThat(auditCount("USER_UPDATED")).isOne();
    }

    @Test
    void aDisabledUserCannotSignInAndTheAnswerMatchesAWrongPassword() {
        String admin = admin("root.admin");
        String target = user("target.user", GlobalRole.USER, false);
        call(admin, null, "UpdateUser", UPDATE_USER, Map.of("id", target, "input", Map.of("enabled", false)));

        var disabled = authenticate("target.user", PASSWORD, "10.0.0.1");
        var wrong = authenticate("target.user", "wrong-password-value", "10.0.0.2");
        var unknown = authenticate("nobody.here", PASSWORD, "10.0.0.3");
        for (var r : List.of(disabled, wrong, unknown)) {
            assertThat(r.data().path("authenticate").path("code").asText()).isEqualTo("UNAUTHENTICATED");
            assertThat(r.data().path("authenticate").path("message").asText())
                    .isEqualTo("The username or password is not correct.");
        }
    }

    @Test
    void reEnablingRestoresAccessOnTheNextCall() {
        String admin = admin("root.admin");
        String target = user("target.user", GlobalRole.USER, false);
        call(admin, null, "UpdateUser", UPDATE_USER, Map.of("id", target, "input", Map.of("enabled", false)));
        assertThat(call(target, null, "Me", ME, Map.of()).data().path("me").isNull()).isTrue();
        call(admin, null, "UpdateUser", UPDATE_USER, Map.of("id", target, "input", Map.of("enabled", true)));
        assertThat(call(target, null, "Me", ME, Map.of()).data().path("me").path("username").asText()).isEqualTo("target.user");
    }

    @Test
    void aTokenForAnAccountThatNoLongerExistsIsUnauthenticated() {
        var r = call(UUID.randomUUID().toString(), null, "Invoices", INVOICES, Map.of());
        assertThat(r.data().path("invoices").path("code").asText()).isEqualTo("UNAUTHENTICATED");
    }
}
