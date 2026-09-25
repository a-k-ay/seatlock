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

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class HoldService {

    private final HoldRepository holdRepository;
    private final SeatRepository seatRepository;
    private final UserRepository userRepository;

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