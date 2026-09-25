package com.seatlock.holds;

import jakarta.validation.constraints.NotNull;

import java.util.UUID;

public record CreateHoldRequest(@NotNull UUID seatId) {}