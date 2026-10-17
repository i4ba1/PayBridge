package com.paybridge.core.identity;

import java.util.List;

/** A domain failure that maps to an OperationProblem. Messages never carry credentials or account details. */
public class IdentityException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public enum Code {
        VALIDATION_ERROR, NOT_FOUND, FORBIDDEN, UNAUTHENTICATED, PASSWORD_CHANGE_REQUIRED, RATE_LIMITED,
        TEMPORARILY_UNAVAILABLE, SETUP_ALREADY_COMPLETED, BOOTSTRAP_TOKEN_INVALID, USERNAME_TAKEN, INVALID_STATE
    }

    private final Code code;
    private final transient List<PasswordPolicy.Problem> fieldProblems;
    private final Long retryAfterSeconds;

    public IdentityException(Code code, String message) {
        this(code, message, List.of(), null);
    }

    public IdentityException(Code code, String message, List<PasswordPolicy.Problem> fieldProblems, Long retryAfterSeconds) {
        super(message);
        this.code = code;
        this.fieldProblems = fieldProblems;
        this.retryAfterSeconds = retryAfterSeconds;
    }

    public Code code() {
        return code;
    }

    public List<PasswordPolicy.Problem> fieldProblems() {
        return fieldProblems;
    }

    public Long retryAfterSeconds() {
        return retryAfterSeconds;
    }
}
