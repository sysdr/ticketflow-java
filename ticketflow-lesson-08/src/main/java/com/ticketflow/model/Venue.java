package com.ticketflow.model;

import jakarta.persistence.*;

/**
 * A concert venue. Holds the total seat count for capacity checks.
 * One venue → many seats (see Seat.java).
 */
@Entity
@Table(name = "venue")
public class Venue {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String name;

    @Column(nullable = false)
    private int totalSeats;

    protected Venue() {}

    public Venue(String name, int totalSeats) {
        this.name = name;
        this.totalSeats = totalSeats;
    }

    public Long getId()         { return id; }
    public String getName()     { return name; }
    public int getTotalSeats()  { return totalSeats; }
}
