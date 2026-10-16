package com.paybridge.domain.payment;

/** A caller asked for a transition the lifecycle does not allow (a programming or sequencing error). */
public class IllegalTransitionException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public IllegalTransitionException(String message) {
        super(message);
    }
}
