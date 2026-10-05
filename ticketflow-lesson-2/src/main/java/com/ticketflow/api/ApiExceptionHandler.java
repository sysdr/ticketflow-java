package com.ticketflow.api;

import com.ticketflow.api.ApiModels.ErrorResponse;
import com.ticketflow.domain.DomainException;
import com.ticketflow.service.RequestIds;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** Turns business-rule failures into honest HTTP statuses, each carrying the request id. */
@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler(DomainException.class)
    public ResponseEntity<ErrorResponse> handle(DomainException ex) {
        HttpStatus status = switch (ex.code()) {
            case NOT_FOUND -> HttpStatus.NOT_FOUND;
            case SEAT_UNAVAILABLE, HOLD_NOT_ACTIVE -> HttpStatus.CONFLICT;
            case HOLD_EXPIRED -> HttpStatus.GONE;
            case INVALID_REQUEST -> HttpStatus.BAD_REQUEST;
        };
        return ResponseEntity.status(status)
                .body(new ErrorResponse(ex.code().name(), ex.getMessage(), RequestIds.current()));
    }
}
