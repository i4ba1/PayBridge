package com.paybridge.core.security;

import com.paybridge.application.port.LoginThrottle;
import java.time.Duration;
import java.util.Locale;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * Key pb:login:fail:{username}:{clientAddress}, a counter with a fixed window set when the first failure is counted
 * (DMR section 8). Over the limit the login is refused until the key expires. Redis errors fail closed.
 */
@Component
public class RedisLoginThrottle implements LoginThrottle {
    private static final Pattern SAFE = Pattern.compile("[^a-z0-9._:-]");

    private final StringRedisTemplate redis;
    private final int maxFailures;
    private final Duration window;

    public RedisLoginThrottle(StringRedisTemplate redis,
                              @Value("${paybridge.security.login-throttle.max-failures:5}") int maxFailures,
                              @Value("${paybridge.security.login-throttle.window-minutes:15}") int windowMinutes) {
        this.redis = redis;
        this.maxFailures = maxFailures;
        this.window = Duration.ofMinutes(windowMinutes);
    }

    static String key(String username, String clientAddress) {
        String u = SAFE.matcher(username.toLowerCase(Locale.ROOT)).replaceAll("_");
        String a = SAFE.matcher((clientAddress == null || clientAddress.isBlank() ? "unknown" : clientAddress)
                .toLowerCase(Locale.ROOT)).replaceAll("_");
        return "pb:login:fail:" + (u.length() > 64 ? u.substring(0, 64) : u) + ":" + (a.length() > 45 ? a.substring(0, 45) : a);
    }

    @Override
    public void check(String username, String clientAddress) {
        try {
            String key = key(username, clientAddress);
            String value = redis.opsForValue().get(key);
            if (value != null && Long.parseLong(value) >= maxFailures) {
                Long ttl = redis.getExpire(key);
                throw new Throttled(ttl == null || ttl <= 0 ? window.toSeconds() : ttl);
            }
        } catch (Throttled t) {
            throw t;
        } catch (RuntimeException e) {
            throw new Unavailable(e);
        }
    }

    @Override
    public boolean recordFailure(String username, String clientAddress) {
        try {
            String key = key(username, clientAddress);
            Long count = redis.opsForValue().increment(key);
            if (count == null) {
                throw new IllegalStateException("no counter value");
            }
            if (count == 1) {
                redis.expire(key, window);
            }
            return count == maxFailures;
        } catch (RuntimeException e) {
            throw new Unavailable(e);
        }
    }

    @Override
    public void reset(String username, String clientAddress) {
        try {
            redis.delete(key(username, clientAddress));
        } catch (RuntimeException e) {
            throw new Unavailable(e);
        }
    }
}
