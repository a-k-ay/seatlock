package com.seatlock.seats;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

public record CreateSeatRequest(
        @NotBlank String section,
        @NotBlank String row,
        @NotBlank String seatNumber,
        @NotNull @PositiveOrZero Long priceAmount,
        @NotBlank @Size(min = 3, max = 3) String priceCurrency
) {}