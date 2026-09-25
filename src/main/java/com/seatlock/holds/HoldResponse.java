package com.seatlock.holds;

import java.time.Instant;
import java.util.UUID;

public record HoldResponse(
        UUID id,
        UUID eventId,
        UUID seatId,
        UUID userId,
        HoldStatus status,
        Long priceAtHoldAmount,
        String priceAtHoldCurrency,
        Instant expiresAt,
        Instant createdAt
) {
    public static HoldResponse from(Hold hold) {
        return new HoldResponse(
                hold.getId(),
                hold.getEvent().getId(),
                hold.getSeat().getId(),
                hold.getUser().getId(),
                hold.getStatus(),
                hold.getPriceAtHoldAmount(),
                hold.getPriceAtHoldCurrency(),
                hold.getExpiresAt(),
                hold.getCreatedAt()
        );
    }
}