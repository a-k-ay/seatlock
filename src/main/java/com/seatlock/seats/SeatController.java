package com.seatlock.seats;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/events/{eventId}/seats")
@RequiredArgsConstructor
public class SeatController {

    private final SeatService seatService;

    @PostMapping
    public ResponseEntity<List<SeatResponse>> createSeats(
            @PathVariable UUID eventId,
            @Valid @RequestBody CreateSeatsRequest request) {
        List<SeatResponse> created = seatService.createSeats(eventId, request);
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    @GetMapping
    public List<SeatResponse> listSeats(@PathVariable UUID eventId) {
        return seatService.listSeats(eventId);
    }
}