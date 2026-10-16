package com.paybridge.domain.payment;

public enum PaymentStatus {
    REQUESTED,
    PROCESSING,
    SUCCEEDED,
    FAILED,
    UNKNOWN;

    public boolean isTerminal() {
        return this == SUCCEEDED || this == FAILED;
    }
}
