package com.paybridge.core.security;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class RedisLoginThrottleKeyTest {

    @Test
    void usesTheDocumentedKeyShape() {
        assertThat(RedisLoginThrottle.key("Target.User", "10.0.0.1")).isEqualTo("pb:login:fail:target.user:10.0.0.1");
    }

    @Test
    void hostileInputCannotInjectKeySeparatorsOrControlCharacters() {
        String key = RedisLoginThrottle.key("evil:*\n\r user", "10.0.0.1\nDEL");
        assertThat(key).matches("pb:login:fail:[a-z0-9._:-]+");
        assertThat(key).doesNotContain("*").doesNotContain("\n").doesNotContain(" ");
    }

    @Test
    void keyLengthIsBounded() {
        assertThat(RedisLoginThrottle.key("a".repeat(5000), "b".repeat(5000)).length()).isLessThan(160);
    }

    @Test
    void aBlankAddressFallsBackToTheSharedBucket() {
        assertThat(RedisLoginThrottle.key("someone", " ")).endsWith(":unknown");
        assertThat(RedisLoginThrottle.key("someone", null)).endsWith(":unknown");
    }
}
