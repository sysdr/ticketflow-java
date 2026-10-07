package com.ticketflow.model;

import jakarta.persistence.*;
import java.time.Instant;

/** A confirmed booking — one buyer, one seat, one timestamp. */
@Entity
@Table(name = "bookings")
public class Booking {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "seat_id", nullable = false)
    private Seat seat;

    @Column(nullable = false)
    private String buyerEmail;

    @Column(nullable = false)
    private Instant createdAt = Instant.now();

    // Which instance processed this booking — set from the MDC instanceId.
    // Lets you query "SELECT instance_id, COUNT(*) FROM bookings GROUP BY instance_id"
    // and confirm both instances are receiving work.
    @Column(nullable = false)
    private String processedBy;

    protected Booking() {}

    public Booking(Seat seat, String buyerEmail, String processedBy) {
        this.seat = seat;
        this.buyerEmail = buyerEmail;
        this.processedBy = processedBy;
    }

    public Long getId()            { return id; }
    public Seat getSeat()          { return seat; }
    public String getBuyerEmail()  { return buyerEmail; }
    public Instant getCreatedAt()  { return createdAt; }
    public String getProcessedBy() { return processedBy; }
}
