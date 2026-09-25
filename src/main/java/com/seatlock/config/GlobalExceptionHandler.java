package com.seatlock.config;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;

@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<Map<String, String>> handleUnique(DataIntegrityViolationException e) {
        String msg = e.getMessage() == null ? "" : e.getMessage();
        if (msg.contains("one_active_hold_per_seat")) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(Map.of("error", "SEAT_UNAVAILABLE"));
        }
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(Map.of("error", "CONSTRAINT_VIOLATION"));
    }

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<Map<String, String>> handleResponseStatus(ResponseStatusException e) {
        return ResponseEntity.status(e.getStatusCode())
                .body(Map.of("error", e.getReason() == null ? "ERROR" : e.getReason()));
    }
}