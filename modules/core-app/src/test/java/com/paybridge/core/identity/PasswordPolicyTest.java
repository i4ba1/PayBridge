package com.paybridge.core.identity;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class PasswordPolicyTest {

    @Test
    void acceptsALongEnoughPassword() {
        assertThat(PasswordPolicy.validate("admin", "A-long-enough-password")).isEmpty();
        assertThat(PasswordPolicy.validate("admin", "x".repeat(12))).isEmpty();
    }

    @Test
    void rejectsShortPasswordsAndNull() {
        assertThat(PasswordPolicy.validate("admin", "x".repeat(11))).extracting(PasswordPolicy.Problem::code).containsExactly("TOO_SHORT");
        assertThat(PasswordPolicy.validate("admin", null)).extracting(PasswordPolicy.Problem::code).containsExactly("TOO_SHORT");
        assertThat(PasswordPolicy.validate("admin", "")).hasSize(1);
    }

    @Test
    void rejectsOverlongPasswords() {
        assertThat(PasswordPolicy.validate("admin", "x".repeat(129))).extracting(PasswordPolicy.Problem::code).containsExactly("TOO_LONG");
        assertThat(PasswordPolicy.validate("admin", "x".repeat(128))).isEmpty();
    }

    @Test
    void rejectsThePasswordEqualToTheUsernameIgnoringCase() {
        assertThat(PasswordPolicy.validate("platform.administrator", "Platform.Administrator"))
                .extracting(PasswordPolicy.Problem::code).containsExactly("SAME_AS_USERNAME");
    }

    @Test
    void rejectsCommonPasswordsIgnoringCase() {
        assertThat(PasswordPolicy.validate("someone", "Password1234")).extracting(PasswordPolicy.Problem::code).containsExactly("TOO_COMMON");
        assertThat(PasswordPolicy.validate("someone", "ADMINISTRATOR")).extracting(PasswordPolicy.Problem::code).containsExactly("TOO_COMMON");
    }

    @Test
    void usernamesFollowTheDatabasePattern() {
        for (String ok : new String[] {"abc", "a.b-c_d", "0day", "admin", "x".repeat(64)}) {
            assertThat(PasswordPolicy.validateUsername(ok)).as(ok).isEmpty();
        }
        for (String bad : new String[] {"ab", "Abc", "a b", "-abc", ".abc", "a@b", "x".repeat(65), "", null, "abc\n"}) {
            assertThat(PasswordPolicy.validateUsername(bad)).as(String.valueOf(bad)).hasSize(1);
        }
    }
}
