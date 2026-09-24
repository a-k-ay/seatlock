package com.seatlock.seats;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;

import java.util.List;

public record CreateSeatsRequest(
        @NotEmpty @Valid List<CreateSeatRequest> seats
) {}