package com.paybridge.it.identity;

import static com.paybridge.it.support.Ops.*;
import static org.assertj.core.api.Assertions.assertThat;

import com.paybridge.application.port.IdentityStore.GlobalRole;
import com.paybridge.core.identity.IdentityException.Code;
import com.paybridge.core.security.Authorizer;
import com.paybridge.core.security.Authorizer.Denied;
import com.paybridge.core.security.Authorizer.Granted;
import com.paybridge.core.security.Authorizer.Requirement;
import com.paybridge.core.security.Authorizer.Requires;
import com.paybridge.core.security.Authorizer.Scope;
import com.paybridge.core.security.ServiceContextVerifier.Claims;
import com.paybridge.it.support.CoreHttpIT;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** FR-003: viewer cannot mutate, operator cannot administer users, and role changes apply on the very next call. */
class RolePermissionIT extends CoreHttpIT {
    @Autowired Authorizer authorizer;

    private Authorizer.Decision decide(String sub, String mid, Requires requires, Scope scope, String field) {
        return authorizer.authorize(new Claims(sub, mid, "Op", "rid", "jti"), "corr-1", new Requirement(field, requires, scope));
    }

    private Code denied(Authorizer.Decision d) {
        assertThat(d).isInstanceOf(Denied.class);
        return ((Denied) d).code();
    }

    @Test
    void merchantRolesAreEvaluatedInTheSelectedMerchant() {
        String a = merchant("a", "IDR");
        String viewer = user("viewer.a", GlobalRole.USER, false);
        String operator = user("operator.a", GlobalRole.USER, false);
        member(viewer, a, "VIEWER");
        member(operator, a, "OPERATOR");

        assertThat(decide(viewer, a, Requires.VIEWER, Scope.MERCHANT, "payments")).isInstanceOf(Granted.class);
        assertThat(denied(decide(viewer, a, Requires.OPERATOR, Scope.MERCHANT, "requestPayment"))).isEqualTo(Code.FORBIDDEN);
        assertThat(decide(operator, a, Requires.VIEWER, Scope.MERCHANT, "payments")).as("operator includes viewer").isInstanceOf(Granted.class);
        assertThat(decide(operator, a, Requires.OPERATOR, Scope.MERCHANT, "requestPayment")).isInstanceOf(Granted.class);
        assertThat(denied(decide(viewer, null, Requires.VIEWER, Scope.MERCHANT, "payments"))).as("no merchant selected").isEqualTo(Code.FORBIDDEN);
    }

    @Test
    void operatorsAndViewersCannotAdministerUsersOverHttp() {
        String a = merchant("a", "IDR");
        String operator = user("operator.a", GlobalRole.USER, false);
        member(operator, a, "OPERATOR");
        Map<String, Object> input = Map.of("input", Map.of("username", "new.user"));

        var r = call(operator, a, "CreateUser", CREATE_USER, input);
        assertThat(r.status()).isEqualTo(200);
        assertThat(r.data().path("createUser").path("code").asText()).isEqualTo("FORBIDDEN");
        assertThat(rootJdbc.queryForObject("SELECT COUNT(*) FROM app_user WHERE username = 'new.user'", Integer.class)).isZero();

        var users = call(operator, a, "Users", USERS, Map.of());
        assertThat(users.data().path("users").path("code").asText()).isEqualTo("FORBIDDEN");
        var lookup = call(operator, a, "User", USER, Map.of("id", operator));
        assertThat(lookup.data().path("user").isNull()).as("a non administrator lookup is not visible").isTrue();
    }

    @Test
    void anAdministratorCreatesUsersWithMembershipsAndTheyCanBeUsedAtOnce() {
        String admin = admin("root.admin");
        String a = merchant("a", "IDR");
        var created = call(admin, null, "CreateUser", CREATE_USER, Map.of("input", Map.of("username", "new.operator",
                "displayName", "New Operator", "memberships", List.of(Map.of("merchantId", a, "role", "OPERATOR")))));
        var payload = created.data().path("createUser");
        assertThat(payload.path("__typename").asText()).isEqualTo("UserCreated");
        assertThat(payload.path("temporaryCredential").asText()).hasSize(20);
        assertThat(payload.path("user").path("passwordChangeRequired").asBoolean()).isTrue();
        assertThat(payload.path("user").path("memberships").get(0).path("role").asText()).isEqualTo("OPERATOR");
        assertThat(auditCount("USER_CREATED")).isOne();
        assertThat(auditCount("MEMBERSHIP_CHANGED")).isOne();

        String newId = payload.path("user").path("id").asText();
        assertThat(decide(newId, a, Requires.OPERATOR, Scope.MERCHANT, "requestPayment")).as("a new account must first change its password")
                .isInstanceOf(Denied.class);
    }

    @Test
    void roleAndMembershipChangesApplyOnTheNextProtectedCall() {
        String admin = admin("root.admin");
        String a = merchant("a", "IDR");
        String b = merchant("b", "USD");
        String target = user("target.user", GlobalRole.USER, false);
        member(target, a, "OPERATOR");
        assertThat(decide(target, a, Requires.OPERATOR, Scope.MERCHANT, "requestPayment")).isInstanceOf(Granted.class);

        var set = call(admin, null, "SetUserMemberships", SET_MEMBERSHIPS, Map.of("id", target, "memberships", List.of(
                Map.of("merchantId", a, "role", "VIEWER"), Map.of("merchantId", b, "role", "OPERATOR"))));
        assertThat(set.data().path("setUserMemberships").path("__typename").asText()).isEqualTo("MembershipsSet");

        assertThat(denied(decide(target, a, Requires.OPERATOR, Scope.MERCHANT, "requestPayment"))).isEqualTo(Code.FORBIDDEN);
        assertThat(decide(target, a, Requires.VIEWER, Scope.MERCHANT, "payments")).isInstanceOf(Granted.class);
        assertThat(decide(target, b, Requires.OPERATOR, Scope.MERCHANT, "requestPayment")).isInstanceOf(Granted.class);
        assertThat(auditCount("MEMBERSHIP_CHANGED")).isEqualTo(2);

        call(admin, null, "SetUserMemberships", SET_MEMBERSHIPS, Map.of("id", target, "memberships", List.of()));
        assertThat(denied(decide(target, b, Requires.VIEWER, Scope.MERCHANT, "payments"))).isEqualTo(Code.FORBIDDEN);
    }

    @Test
    void promotingAnAccountToAdministratorTakesEffectImmediately() {
        String admin = admin("root.admin");
        String target = user("target.user", GlobalRole.USER, false);
        assertThat(denied(decide(target, null, Requires.ADMIN, Scope.GLOBAL, "users"))).isEqualTo(Code.FORBIDDEN);
        var r = call(admin, null, "UpdateUser", UPDATE_USER, Map.of("id", target, "input", Map.of("admin", true)));
        assertThat(r.data().path("updateUser").path("user").path("admin").asBoolean()).isTrue();
        assertThat(decide(target, null, Requires.ADMIN, Scope.GLOBAL, "users")).isInstanceOf(Granted.class);
        assertThat(auditCount("USER_UPDATED")).isOne();
    }

    @Test
    void anAdministratorCannotDisableOrDemoteThemselves() {
        String admin = admin("root.admin");
        var r = call(admin, null, "UpdateUser", UPDATE_USER, Map.of("id", admin, "input", Map.of("enabled", false)));
        assertThat(r.data().path("updateUser").path("code").asText()).isEqualTo("INVALID_STATE");
        var d = call(admin, null, "UpdateUser", UPDATE_USER, Map.of("id", admin, "input", Map.of("admin", false)));
        assertThat(d.data().path("updateUser").path("code").asText()).isEqualTo("INVALID_STATE");
    }

    @Test
    void usernamesAreUniqueAndValidated() {
        String admin = admin("root.admin");
        user("taken.name", GlobalRole.USER, false);
        var dup = call(admin, null, "CreateUser", CREATE_USER, Map.of("input", Map.of("username", "taken.name")));
        assertThat(dup.data().path("createUser").path("code").asText()).isEqualTo("USERNAME_TAKEN");
        var bad = call(admin, null, "CreateUser", CREATE_USER, Map.of("input", Map.of("username", "Bad Name")));
        assertThat(bad.data().path("createUser").path("code").asText()).isEqualTo("VALIDATION_ERROR");
    }

    @Test
    void usersListPagesByUsername() {
        String admin = admin("root.admin");
        for (int i = 0; i < 4; i++) {
            user("member" + i + ".user", GlobalRole.USER, false);
        }
        var first = call(admin, null, "Users", USERS, Map.of("limit", 2));
        var page = first.data().path("users");
        assertThat(page.path("items")).hasSize(2);
        String next = page.path("page").path("nextCursor").asText();
        assertThat(next).isNotBlank();
        var second = call(admin, null, "Users", USERS, Map.of("limit", 10, "cursor", next));
        assertThat(second.data().path("users").path("items")).hasSize(3);
        assertThat(second.data().path("users").path("page").path("nextCursor").isNull()).isTrue();
        var tooMany = call(admin, null, "Users", USERS, Map.of("limit", 101));
        assertThat(tooMany.data().path("users").path("code").asText()).isEqualTo("VALIDATION_ERROR");
    }
}
