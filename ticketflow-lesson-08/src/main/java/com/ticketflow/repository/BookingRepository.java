package com.ticketflow.repository;

import com.ticketflow.model.Booking;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface BookingRepository extends JpaRepository<Booking, Long> {

    /**
     * Per-instance booking distribution (Lesson 6 feature, still works here).
     * Returns Object[] rows: [processedBy (String), count (Long)]
     */
    @Query("SELECT b.processedBy, COUNT(b) FROM Booking b GROUP BY b.processedBy")
    List<Object[]> countByInstance();

    /**
     * Double-booking check: any seat booked more than once.
     * Should always return empty. Non-empty = data integrity failure.
     */
    @Query("""
           SELECT b.seat.id FROM Booking b
           GROUP BY b.seat.id
           HAVING COUNT(b) > 1
           """)
    List<Long> findDuplicateBookings();

    /**
     * Homework support: all bookings for a venue, newest first.
     * Joins Booking → Seat → Venue to filter by venueId.
     */
    @Query("""
           SELECT b FROM Booking b
           JOIN FETCH b.seat s
           WHERE s.venue.id = :venueId
           ORDER BY b.createdAt DESC
           """)
    List<Booking> findByVenueIdOrderByCreatedAtDesc(@Param("venueId") Long venueId);
}
