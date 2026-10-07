package com.ticketflow.model;

import jakarta.persistence.*;

/** One physical seat in a venue. Status drives the booking state machine. */
@Entity
@Table(name = "seats", indexes = {
    @Index(name = "idx_seats_venue_status", columnList = "venue_id, status")
})
public class Seat {

    public enum Status { AVAILABLE, HELD, BOOKED }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "venue_id", nullable = false)
    private Venue venue;

    @Column(nullable = false)
    private String seatNumber;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Status status = Status.AVAILABLE;

    protected Seat() {}

    public Seat(Venue venue, String seatNumber) {
        this.venue = venue;
        this.seatNumber = seatNumber;
    }

    public Long getId()           { return id; }
    public Venue getVenue()       { return venue; }
    public String getSeatNumber() { return seatNumber; }
    public Status getStatus()     { return status; }
    public void setStatus(Status s) { this.status = s; }
}
