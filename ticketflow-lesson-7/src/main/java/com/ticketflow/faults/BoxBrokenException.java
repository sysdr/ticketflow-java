package com.ticketflow.faults;

/** Thrown when the box has been switched into fail-fast mode. Becomes HTTP 503. */
public class BoxBrokenException extends RuntimeException {

    public BoxBrokenException(String node) {
        super(node + " is in fail-fast mode and refuses every booking");
    }
}
