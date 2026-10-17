package com.paybridge.it.identity;

import static com.paybridge.it.support.Ops.*;
import static org.assertj.core.api.Assertions.assertThat;

import com.paybridge.application.port.IdentityStore.GlobalRole;
import com.paybridge.core.security.Authorizer;
import com.paybridge.core.security.Authorizer.Denied;
import com.paybridge.core.security.Authorizer.Granted;
import com.paybridge.core.security.Authorizer.Requirement;
import com.paybridge.core.security.Authorizer.Requires;
import com.paybridge.core.security.Authorizer.Scope;
import com.paybridge.core.security.ServiceContextVerifier.Claims;
import com.paybridge.it.support.CoreHttpIT;
import com.paybridge.it.support.TestKeys;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * FR-004: merchants are isolated. A member never reaches another merchant, supplying another merchant id or other
 * headers grants nothing, and an administrator's cross merchant read is explicit and audited.
 */
class CrossMerchantAccessIT extends CoreHttpIT {
    @Autowired Authorizer authorizer;

    private Authorizer.Decision decide(String sub, String mid, Requires requires, Scope scope) {
        return authorizer.authorize(new Claims(sub, mid, "Op", "rid", "jti"), "corr-xm", new Requirement("payments", requires, scope));
    }

    @Test
    void aMemberOfAIsRefusedInB() {
        String a = merchant("a", "IDR");
        String b = merchant("b", "USD");
        String operatorA = user("operator.a", GlobalRole.USER, false);
        member(operatorA, a, "OPERATOR");

        assertThat(decide(operatorA, a, Requires.OPERATOR, Scope.MERCHANT)).isInstanceOf(Granted.class);
        assertThat(decide(operatorA, b, Requires.VIEWER, Scope.MERCHANT)).isInstanceOf(Denied.class);
        assertThat(decide(operatorA, b, Requires.OPERATOR, Scope.MERCHANT)).isInstanceOf(Denied.class);
        assertThat(auditCount("ADMIN_CROSS_MERCHANT_ACCESS")).isZero();
    }

    @Test
    void anUnknownMerchantAndAMerchantForgedInHeadersGrantNothing() {
        String a = merchant("a", "IDR");
        String b = merchant("b", "USD");
        String operatorA = user("operator.a", GlobalRole.USER, false);
        member(operatorA, a, "OPERATOR");

        assertThat(decide(operatorA, UUID.randomUUID().toString(), Requires.VIEWER, Scope.MERCHANT)).isInstanceOf(Denied.class);

        // Headers are not an input to authorization: the merchant comes from the signed claim only.
        var r = post(token(operatorA, a, "Invoices"), INVOICES, Map.of(), "Invoices",
                Map.of("X-Merchant-Id", b, "X-Forwarded-For", "10.0.0.9", "X-Role", "ADMIN"));
        assertThat(r.body().toString()).doesNotContain(b);
        assertThat(auditCount("ADMIN_CROSS_MERCHANT_ACCESS")).isZero();
    }

    @Test
    void aTokenCannotCarryAuthorityAndSpoofedClaimsAreRejected() {
        String a = merchant("a", "IDR");
        String operatorA = user("operator.a", GlobalRole.USER, false);
        member(operatorA, a, "OPERATOR");
        var claims = TestKeys.claims(operatorA, a, "Me", Instant.now().getEpochSecond());
        claims.put("admin", true);
        assertThat(post(TestKeys.sign(KEY, claims), ME, Map.of(), "Me", Map.of()).status()).isEqualTo(401);
        claims.remove("admin");
        claims.put("role", "ADMIN");
        assertThat(post(TestKeys.sign(KEY, claims), ME, Map.of(), "Me", Map.of()).status()).isEqualTo(401);
    }

    @Test
    void anAdministratorMayReadASelectedMerchantAndEveryReadIsAudited() {
        String a = merchant("a", "IDR");
        String admin = admin("root.admin");

        var first = decide(admin, a, Requires.VIEWER, Scope.MERCHANT);
        assertThat(first).isInstanceOf(Granted.class);
        assertThat(((Granted) first).actor().merchantId()).isEqualTo(a);
        decide(admin, a, Requires.VIEWER, Scope.MERCHANT);
        assertThat(auditCount("ADMIN_CROSS_MERCHANT_ACCESS")).isEqualTo(2);
        var row = rootJdbc.queryForMap("SELECT actor_user_id, merchant_id, target_id, result, correlation_id FROM audit_action "
                + "WHERE action = 'ADMIN_CROSS_MERCHANT_ACCESS' LIMIT 1");
        assertThat(row.get("actor_user_id")).isEqualTo(admin);
        assertThat(row.get("merchant_id")).isEqualTo(a);
        assertThat(row.get("correlation_id")).isEqualTo("corr-xm");
    }

    @Test
    void anAdministratorWithoutMembershipCannotPerformOperatorActionsOrOmitTheMerchant() {
        String a = merchant("a", "IDR");
        String admin = admin("root.admin");
        assertThat(decide(admin, a, Requires.OPERATOR, Scope.MERCHANT)).as("mutations need a real OPERATOR membership").isInstanceOf(Denied.class);
        assertThat(decide(admin, null, Requires.VIEWER, Scope.MERCHANT)).as("the merchant must be named").isInstanceOf(Denied.class);
        assertThat(auditCount("ADMIN_CROSS_MERCHANT_ACCESS")).isZero();
    }

    @Test
    void adminOperationsOnASelectedMerchantAuditTheSelection() {
        String a = merchant("a", "IDR");
        String admin = admin("root.admin");
        String operator = user("operator.a", GlobalRole.USER, false);
        member(operator, a, "OPERATOR");
        assertThat(decide(admin, a, Requires.ADMIN, Scope.MERCHANT)).isInstanceOf(Granted.class);
        assertThat(auditCount("ADMIN_CROSS_MERCHANT_ACCESS")).isOne();
        assertThat(decide(operator, a, Requires.ADMIN, Scope.MERCHANT)).isInstanceOf(Denied.class);
    }

    @Test
    void myMerchantsListsOnlyTheCallersOwn() {
        String a = merchant("a", "IDR");
        String b = merchant("b", "USD");
        String operatorA = user("operator.a", GlobalRole.USER, false);
        String viewerB = user("viewer.b", GlobalRole.USER, false);
        member(operatorA, a, "OPERATOR");
        member(viewerB, b, "VIEWER");
        var r = call(operatorA, null, "MyMerchants", MY_MERCHANTS, Map.of());
        assertThat(r.data().path("myMerchants")).hasSize(1);
        assertThat(r.data().path("myMerchants").get(0).path("id").asText()).isEqualTo(a);
        assertThat(r.data().path("myMerchants").get(0).path("role").asText()).isEqualTo("OPERATOR");
    }
}
