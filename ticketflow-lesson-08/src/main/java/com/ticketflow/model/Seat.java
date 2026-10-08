package com.ticketflow.model;

import jakarta.persistence.*;

/**
 * One physical seat inside a Venue.
 *
 * Status transitions:
 *   AVAILABLE → BOOKED   (via bookSeat — pessimistic lock prevents double-sell)
 *
 * HELD is scaffolded for Day 2's original model but not used yet — it will
 * matter in Lesson 16 (idempotency) when we add a hold-then-confirm flow.
 */
@Entity
@Table(name = "seat",
       indexes = { @Index(name = "idx_seat_venue_status", columnList = "venue_id, status") })
public class Seat {

    public enum Status { AVAILABLE, HELD, BOOKED }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "venue_id", nullable = false)
    private Venue venue;

    @Column(name = "seat_number", nullable = false, length = 8)
    private String seatNumber;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private Status status = Status.AVAILABLE;

    protected Seat() {}

    public Seat(Venue venue, String seatNumber) {
        this.venue = venue;
        this.seatNumber = seatNumber;
    }

    public Long getId()          { return id; }
    public Venue getVenue()      { return venue; }
    public String getSeatNumber(){ return seatNumber; }
    public Status getStatus()    { return status; }

    public void setStatus(Status status) { this.status = status; }
}
