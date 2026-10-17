package com.paybridge.core.identity;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/** Proposed policy (DMR section 3): at least 12 characters, not the username, not on a small common list. */
public final class PasswordPolicy {
    public static final int MIN_LENGTH = 12;
    public static final int MAX_LENGTH = 128;
    /** Same pattern as the database check ck_app_user_username. */
    public static final Pattern USERNAME = Pattern.compile("^[a-z0-9][a-z0-9._-]{2,63}$");

    private static final Set<String> COMMON = Set.of("password1234", "123456789012", "qwertyuiop12", "administrator",
            "letmein12345", "welcome12345", "password12345", "paybridge1234", "changeme1234", "iloveyou1234");

    public record Problem(String field, String code, String message) {}

    private PasswordPolicy() {}

    public static List<Problem> validate(String username, String password) {
        var problems = new ArrayList<Problem>();
        if (password == null || password.length() < MIN_LENGTH) {
            problems.add(new Problem("password", "TOO_SHORT", "The password must have at least " + MIN_LENGTH + " characters."));
        } else if (password.length() > MAX_LENGTH) {
            problems.add(new Problem("password", "TOO_LONG", "The password must have at most " + MAX_LENGTH + " characters."));
        } else {
            String lower = password.toLowerCase(Locale.ROOT);
            if (username != null && lower.equals(username.toLowerCase(Locale.ROOT))) {
                problems.add(new Problem("password", "SAME_AS_USERNAME", "The password must differ from the username."));
            }
            if (COMMON.contains(lower)) {
                problems.add(new Problem("password", "TOO_COMMON", "The password is too common."));
            }
        }
        return problems;
    }

    public static List<Problem> validateUsername(String username) {
        if (username == null || !USERNAME.matcher(username).matches()) {
            return List.of(new Problem("username", "INVALID_FORMAT",
                    "Use 3 to 64 lowercase letters, digits, dots, underscores or hyphens, starting with a letter or digit."));
        }
        return List.of();
    }
}
