package dev.ticketflow.domain;

import java.time.Instant;

public record DomainEvent(Instant at, String type, String holdId, String seat, String buyer) {}
