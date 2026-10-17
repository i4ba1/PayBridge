package com.paybridge.it.identity;

import static org.assertj.core.api.Assertions.assertThat;

import com.paybridge.application.port.IdentityStore.GlobalRole;
import com.paybridge.it.support.CoreHttpIT;
import com.paybridge.it.support.RedisSupport;
import org.junit.jupiter.api.Test;

/** Failed sign ins are limited per username and client address; Redis trouble fails closed. */
class LoginThrottleIT extends CoreHttpIT {

    private String code(Response r) {
        return r.data().path("authenticate").path("code").asText();
    }

    @Test
    void fiveFailuresBlockTheSixthEvenWithTheRightPassword() {
        user("target.user", GlobalRole.USER, false);
        for (int i = 0; i < 5; i++) {
            assertThat(code(authenticate("target.user", "wrong-password-" + i, "10.9.9.9"))).isEqualTo("UNAUTHENTICATED");
        }
        var blocked = authenticate("target.user", PASSWORD, "10.9.9.9");
        assertThat(code(blocked)).isEqualTo("RATE_LIMITED");
        int retry = blocked.data().path("authenticate").path("retryAfterSeconds").asInt();
        assertThat(retry).isBetween(1, 15 * 60);
        assertThat(redis.getExpire("pb:login:fail:target.user:10.9.9.9")).isPositive();
    }

    @Test
    void theLimitIsPerUsernameAndPerClientAddress() {
        user("target.user", GlobalRole.USER, false);
        user("other.user", GlobalRole.USER, false);
        for (int i = 0; i < 5; i++) {
            authenticate("target.user", "wrong-password-" + i, "10.9.9.9");
        }
        assertThat(code(authenticate("target.user", PASSWORD, "10.9.9.9"))).isEqualTo("RATE_LIMITED");
        assertThat(authenticate("target.user", PASSWORD, "10.8.8.8").data().path("authenticate").path("__typename").asText())
                .as("same user from another address").isEqualTo("Authenticated");
        assertThat(authenticate("other.user", PASSWORD, "10.9.9.9").data().path("authenticate").path("__typename").asText())
                .as("another user from the same address").isEqualTo("Authenticated");
    }

    @Test
    void aSuccessfulSignInResetsTheCounter() {
        user("target.user", GlobalRole.USER, false);
        for (int i = 0; i < 4; i++) {
            authenticate("target.user", "wrong-password-" + i, "10.7.7.7");
        }
        assertThat(authenticate("target.user", PASSWORD, "10.7.7.7").data().path("authenticate").path("__typename").asText())
                .isEqualTo("Authenticated");
        assertThat(redis.hasKey("pb:login:fail:target.user:10.7.7.7")).isFalse();
        for (int i = 0; i < 4; i++) {
            assertThat(code(authenticate("target.user", "wrong-again-" + i, "10.7.7.7"))).isEqualTo("UNAUTHENTICATED");
        }
    }

    @Test
    void unknownUsernamesAreCountedTooAndThrottledTheSameWay() {
        for (int i = 0; i < 5; i++) {
            authenticate("no.such.user", "wrong-password-" + i, "10.6.6.6");
        }
        assertThat(code(authenticate("no.such.user", "whatever-password", "10.6.6.6"))).isEqualTo("RATE_LIMITED");
    }

    @Test
    void theThrottleIsAuditedOncePerWindow() {
        user("target.user", GlobalRole.USER, false);
        for (int i = 0; i < 9; i++) {
            authenticate("target.user", "wrong-password-" + i, "10.5.5.5");
        }
        assertThat(auditCount("LOGIN_FAILED")).isOne();
        var row = rootJdbc.queryForMap("SELECT actor_type, result, target_id FROM audit_action WHERE action = 'LOGIN_FAILED'");
        assertThat(row.get("actor_type")).isEqualTo("SERVICE");
        assertThat(row.get("result")).isEqualTo("REJECTED");
        assertThat(row.get("target_id")).isEqualTo("target.user");
    }

    @Test
    void awkwardUsernamesAndAddressesCannotBreakOrForgeKeys() {
        for (int i = 0; i < 5; i++) {
            authenticate("Evil:User\nwith spaces", "wrong-password-" + i, "10.4.4.4");
        }
        assertThat(code(authenticate("Evil:User\nwith spaces", "wrong", "10.4.4.4"))).isEqualTo("RATE_LIMITED");
        assertThat(redis.keys("pb:login:fail:*")).allMatch(k -> k.matches("pb:login:fail:[a-z0-9._:-]+"));
        // An invalid address header degrades to the shared "unknown" bucket rather than failing.
        var r = post(token(null, null, "Authenticate"), AUTHENTICATE, java.util.Map.of("input", java.util.Map.of("username", "x.y.z",
                "password", "abc")), "Authenticate", java.util.Map.of("X-Client-Address", "not an address!"));
        assertThat(code(r)).isEqualTo("UNAUTHENTICATED");
    }

    @Test
    void whenRedisIsUnavailableSignInFailsClosed() {
        user("target.user", GlobalRole.USER, false);
        RedisSupport.pause();
        try {
            var r = authenticate("target.user", PASSWORD, "10.3.3.3");
            assertThat(code(r)).isEqualTo("TEMPORARILY_UNAVAILABLE");
        } finally {
            RedisSupport.unpause();
        }
        assertThat(authenticate("target.user", PASSWORD, "10.3.3.3").data().path("authenticate").path("__typename").asText())
                .as("recovers when Redis returns").isEqualTo("Authenticated");
    }
}
