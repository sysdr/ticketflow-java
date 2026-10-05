package com.ticketflow.domain;

/**
 * A business-rule violation. Carries a machine-readable code so the API layer
 * can map it to an HTTP status without parsing message text.
 */
public class DomainException extends RuntimeException {

    public enum Code {
        NOT_FOUND,
        SEAT_UNAVAILABLE,
        HOLD_EXPIRED,
        HOLD_NOT_ACTIVE,
        INVALID_REQUEST
    }

    private final Code code;

    public DomainException(Code code, String message) {
        super(message);
        this.code = code;
    }

    public Code code() {
        return code;
    }
}
