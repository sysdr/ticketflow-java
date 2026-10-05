package dev.ticketflow.domain;

/**
 * ACTIVE is the only state that can still change. A hold whose deadline has passed is
 * already dead the instant the clock says so, even while its state still reads ACTIVE
 * and before anything has moved it to EXPIRED.
 */
public enum HoldState { ACTIVE, CONFIRMED, RELEASED, EXPIRED }
