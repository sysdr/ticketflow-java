package com.ticketflow.repository;

import com.ticketflow.model.Seat;
import com.ticketflow.model.Seat.Status;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface SeatRepository extends JpaRepository<Seat, Long> {

    /** Count available seats for a venue — used by the booking service. */
    long countByVenueIdAndStatus(Long venueId, Status status);

    /** Fetch one available seat for a venue — used by the naive booking path. */
    @Query("SELECT s FROM Seat s WHERE s.venue.id = :venueId AND s.status = 'AVAILABLE' ORDER BY s.id LIMIT 1")
    Optional<Seat> findFirstAvailable(@Param("venueId") Long venueId);

    /** All seats for a venue — used by the seat-map endpoint (intentionally slow). */
    List<Seat> findByVenueIdOrderBySeatNumber(Long venueId);
}
