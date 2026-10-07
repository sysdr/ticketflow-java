package com.ticketflow.model;

import jakarta.persistence.*;

/** A concert venue — home to one or more seat sections. */
@Entity
@Table(name = "venues")
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

    public Long getId()          { return id; }
    public String getName()      { return name; }
    public int getTotalSeats()   { return totalSeats; }
}
