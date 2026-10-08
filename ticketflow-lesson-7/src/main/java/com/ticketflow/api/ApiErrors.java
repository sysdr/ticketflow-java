package com.ticketflow.api;

import com.ticketflow.checkout.SessionLostException;
import com.ticketflow.domain.SeatTakenException;
import com.ticketflow.faults.BoxBrokenException;
import com.ticketflow.lifecycle.StageTimer;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** Turns the exceptions the core throws into HTTP answers. */
@RestControllerAdvice
public class ApiErrors {

    public record ApiError(String error, String message) {
    }

    @ExceptionHandler(SeatTakenException.class)
    public ResponseEntity<ApiError> seatTaken(SeatTakenException taken, HttpServletRequest request) {
        ResponseEntity.BodyBuilder response = ResponseEntity.status(HttpStatus.CONFLICT);
        if (request.getAttribute(StageTimer.ATTRIBUTE) instanceof StageTimer timer) {
            response.header("Server-Timing", timer.serverTiming());
        }
        return response.body(new ApiError("SEAT_TAKEN", taken.getMessage()));
    }

    @ExceptionHandler(SessionLostException.class)
    public ResponseEntity<ApiError> sessionLost(SessionLostException lost) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(new ApiError("SESSION_" + lost.reason().toUpperCase(), lost.getMessage()));
    }

    @ExceptionHandler(BoxBrokenException.class)
    public ResponseEntity<ApiError> broken(BoxBrokenException broken) {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(new ApiError("BOX_BROKEN", broken.getMessage()));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiError> badRequest(IllegalArgumentException invalid) {
        return ResponseEntity.badRequest().body(new ApiError("BAD_REQUEST", invalid.getMessage()));
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<ApiError> conflict(IllegalStateException busy) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(new ApiError("CONFLICT", busy.getMessage()));
    }
}
