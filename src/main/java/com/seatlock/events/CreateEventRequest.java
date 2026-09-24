package com.seatlock.events;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.time.Instant;

public record CreateEventRequest(
        @NotBlank String name,
        @NotBlank String venue,
        @NotNull Instant startTime,
        @NotNull Instant endTime
) {}