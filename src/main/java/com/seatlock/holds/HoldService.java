package com.seatlock.holds;

import com.seatlock.auth.User;
import com.seatlock.auth.UserRepository;
import com.seatlock.seats.Seat;
import com.seatlock.seats.SeatRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.UUID;
import java.util.List;
import java.util.ArrayList;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class HoldService {

    private final HoldRepository holdRepository;
    private final SeatRepository seatRepository;
    private final UserRepository userRepository;
    private final ObjectMapper objectMapper;

    @Value("${seatlock.holds.ttl-minutes}")
    private long ttlMinutes;

    @Transactional
    public HoldResponse createHold(CreateHoldRequest request) {
        UUID currentUserId = currentUserId();

        Seat seat = seatRepository.findById(request.seatId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "Seat not found: " + request.seatId()));

        User user = userRepository.findById(currentUserId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED,
                        "Authenticated user not found"));

        Hold hold = Hold.builder()
                .event(seat.getEvent())
                .seat(seat)
                .user(user)
                .status(HoldStatus.ACTIVE)
                .priceAtHoldAmount(seat.getBasePriceAmount())
                .priceAtHoldCurrency(seat.getBasePriceCurrency())
                .expiresAt(Instant.now().plus(ttlMinutes, ChronoUnit.MINUTES))
                .build();

            Hold saved = holdRepository.saveAndFlush(hold);
            return HoldResponse.from(saved);

    }

        @Transactional
        public List<HoldResponse> createBulkHolds(CreateBulkHoldRequest request) {
        UUID currentUserId = currentUserId();

        // (1) Option B pre-check — which of these seats already have an ACTIVE hold?
        List<Hold> conflicts = holdRepository.findByStatusAndSeatIdIn(
                HoldStatus.ACTIVE, request.seatIds()
        );
        if (!conflicts.isEmpty()) {
                List<UUID> unavailable = conflicts.stream()
                        .map(h -> h.getSeat().getId())
                        .toList();
                throw new SeatsUnavailableException(unavailable);
        }

        User user = userRepository.findById(currentUserId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED,
                        "Authenticated user not found"));

        // (2) Insert loop — same shape as createHold, one per seat
        List<HoldResponse> results = new ArrayList<>();
        for (UUID seatId : request.seatIds()) {
                Seat seat = seatRepository.findById(seatId)
                        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                                "Seat not found: " + seatId));

                Hold hold = Hold.builder()
                        .event(seat.getEvent())
                        .seat(seat)
                        .user(user)
                        .status(HoldStatus.ACTIVE)
                        .priceAtHoldAmount(seat.getBasePriceAmount())
                        .priceAtHoldCurrency(seat.getBasePriceCurrency())
                        .expiresAt(Instant.now().plus(ttlMinutes, ChronoUnit.MINUTES))
                        .build();

                Hold saved = holdRepository.saveAndFlush(hold);
                results.add(HoldResponse.from(saved));
        }

        return results;
        }

    private UUID currentUserId() {
        Object principal = SecurityContextHolder.getContext()
                .getAuthentication().getPrincipal();
        if (principal instanceof UUID uuid) {
            return uuid;
        }
        throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,
                "No authenticated user");
    }


    
}