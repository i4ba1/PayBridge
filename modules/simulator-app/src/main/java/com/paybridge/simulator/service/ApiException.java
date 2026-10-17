package com.paybridge.simulator.service;

/** An error answered as {"error":{"code":"...","message":"..."}} (API contract 8.1). */
public class ApiException extends RuntimeException {
    private static final long serialVersionUID = 1L;
    private final int status;
    private final String code;

    public ApiException(int status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    public int status() {
        return status;
    }

    public String code() {
        return code;
    }

    public static ApiException invalid(String message) {
        return new ApiException(400, "invalid_request", message);
    }
}
