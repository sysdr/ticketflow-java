package com.ticketflow.repository;

import com.ticketflow.model.Booking;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;

public interface BookingRepository extends JpaRepository<Booking, Long> {

    /** Distribution check: how many bookings each instance processed. */
    @Query("SELECT b.processedBy, COUNT(b) FROM Booking b GROUP BY b.processedBy")
    List<Object[]> countByInstance();

    /** Duplicate check: any seat booked more than once. */
    @Query("SELECT b.seat.id, COUNT(b) FROM Booking b GROUP BY b.seat.id HAVING COUNT(b) > 1")
    List<Object[]> findDuplicateBookings();
}
