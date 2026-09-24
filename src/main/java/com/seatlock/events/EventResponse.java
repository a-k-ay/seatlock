package com.seatlock.events;

import java.time.Instant;
import java.util.UUID;

public record EventResponse(
        UUID id,
        String name,
        String venue,
        Instant startTime,
        Instant endTime,
        EventStatus status,
        Instant createdAt,
        Instant updatedAt
) {
    public static EventResponse from(Event event) {
        return new EventResponse(
                event.getId(),
                event.getName(),
                event.getVenue(),
                event.getStartTime(),
                event.getEndTime(),
                event.getStatus(),
                event.getCreatedAt(),
                event.getUpdatedAt()
        );
    }
}