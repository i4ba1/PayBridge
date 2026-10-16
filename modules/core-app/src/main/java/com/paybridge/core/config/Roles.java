package com.paybridge.core.config;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.core.env.Environment;

/** The roles this process runs, from {@code paybridge.roles} (env PAYBRIDGE_ROLES), comma separated. */
public record Roles(Set<String> names) {
    public static final Set<String> KNOWN = Set.of("api", "relay", "intake", "executor", "projection", "migrate", "seed");
    private static final Set<String> ONE_SHOT = Set.of("migrate", "seed");

    public static Roles from(Environment env) {
        String raw = env.getProperty("paybridge.roles", "api");
        Set<String> names = Arrays.stream(raw.split(",")).map(String::strip).filter(s -> !s.isEmpty())
                .collect(Collectors.toUnmodifiableSet());
        for (String n : names) {
            if (!KNOWN.contains(n)) {
                throw new IllegalArgumentException("unknown role: " + n);
            }
        }
        return new Roles(names);
    }

    public boolean has(String role) {
        return names.contains(role);
    }

    /** True when every configured role runs once and exits. */
    public boolean oneShot() {
        return !names.isEmpty() && ONE_SHOT.containsAll(names);
    }
}
