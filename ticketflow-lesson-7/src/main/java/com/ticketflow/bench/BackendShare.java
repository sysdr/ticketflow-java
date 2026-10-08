package com.ticketflow.bench;

/**
 * How much of a run's traffic the balancer gave to one box.
 *
 * @param sent   requests forwarded to the box
 * @param failed of those, how many came back as HTTP 5xx or not at all
 */
public record BackendShare(String name, long sent, long failed) {
}
