package com.ticketflow.model;

import jakarta.persistence.*;
import java.time.Instant;

/**
 * A confirmed booking — the record the buyer gets and the one we can never lose.
 *
 * processedBy: the INSTANCE_ID of the app node that handled the request.
 * Kept for distribution queries from Lesson 6; also survives restarts now.
 */
@Entity
@Table(name = "booking",
       indexes = { @Index(name = "idx_booking_seat", columnList = "seat_id") })
public class Booking {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "seat_id", nullable = false)
    private Seat seat;

    @Column(name = "buyer_email", nullable = false)
    private String buyerEmail;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "processed_by", length = 64)
    private String processedBy;

    protected Booking() {}

    public Booking(Seat seat, String buyerEmail, String processedBy) {
        this.seat = seat;
        this.buyerEmail = buyerEmail;
        this.processedBy = processedBy;
    }

    public Long getId()           { return id; }
    public Seat getSeat()         { return seat; }
    public String getBuyerEmail() { return buyerEmail; }
    public Instant getCreatedAt() { return createdAt; }
    public String getProcessedBy(){ return processedBy; }
}
