package com.seatlock.seats;

import java.time.Instant;
import java.util.UUID;

public record SeatResponse(
        UUID id,
        UUID eventId,
        String section,
        String row,
        String seatNumber,
        Long basePriceMinorUnits,
        String basePriceCurrency,
        Instant createdAt
) {
    public static SeatResponse from(Seat seat) {
        return new SeatResponse(
                seat.getId(),
                seat.getEvent().getId(),
                seat.getSection(),
                seat.getRow(),
                seat.getSeatNumber(),
                seat.getBasePriceAmount(),
                seat.getBasePriceCurrency(),
                seat.getCreatedAt()
        );
    }
}