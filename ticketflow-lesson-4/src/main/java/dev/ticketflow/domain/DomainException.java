package dev.ticketflow.domain;

/** A business-rule rejection, carrying the HTTP status the API should answer with. */
public final class DomainException extends RuntimeException {

    public enum Code {
        BAD_REQUEST(400), UNKNOWN_SEAT(404), UNKNOWN_HOLD(404),
        SEAT_UNAVAILABLE(409), HOLD_NOT_ACTIVE(409), HOLD_EXPIRED(410);

        private final int status;

        Code(int status) { this.status = status; }

        public int status() { return status; }
    }

    private final Code code;

    public DomainException(Code code, String message) {
        super(message);
        this.code = code;
    }

    public Code code() { return code; }
}
