package com.seatlock.holds;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.UUID;

public record CreateBulkHoldRequest(
        @NotEmpty @Size(min = 1, max = 10) List<UUID> seatIds
) {}