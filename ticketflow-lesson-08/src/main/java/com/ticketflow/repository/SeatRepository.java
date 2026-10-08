package com.ticketflow.repository;

import com.ticketflow.model.Seat;
import com.ticketflow.model.Seat.Status;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface SeatRepository extends JpaRepository<Seat, Long> {

    /**
     * Grab the first available seat for a venue and lock the row immediately.
     *
     * SELECT … FOR UPDATE prevents two concurrent transactions from picking the
     * same seat. This is a pessimistic lock — correct and conservative. Day 13
     * will explore the optimistic alternative (version column + retry) and compare
     * the two approaches under real contention.
     *
     * LIMIT 1 in JPQL requires a Pageable; easier to express in native SQL here.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query(value = """
            SELECT s FROM Seat s
            WHERE s.venue.id = :venueId
              AND s.status   = com.ticketflow.model.Seat.Status.AVAILABLE
            ORDER BY s.seatNumber
            """)
    List<Seat> findAvailableForVenue(@Param("venueId") Long venueId);

    /**
     * Count seats by venue and status — used by the seat-map summary.
     */
    long countByVenueIdAndStatus(Long venueId, Status status);

    /**
     * Full ordered seat list for the seat-map view.
     */
    List<Seat> findByVenueIdOrderBySeatNumber(Long venueId);
}
