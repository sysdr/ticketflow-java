package com.ticketflow.checkout;

/**
 * Thrown when a confirm arrives with a session this box cannot use. Becomes HTTP 401.
 *
 * <p>{@code reason} is one of {@code missing}, {@code unknown} (a server-side
 * session this box never saw), {@code tampered}, {@code expired} or {@code malformed}.
 */
public class SessionLostException extends RuntimeException {

    private final String reason;

    public SessionLostException(String reason, String message) {
        super(message);
        this.reason = reason;
    }

    public String reason() {
        return reason;
    }
}
