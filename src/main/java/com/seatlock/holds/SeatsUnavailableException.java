package com.seatlock.holds;

import java.util.List;
import java.util.UUID;

public class SeatsUnavailableException extends RuntimeException {

    private final List<UUID> unavailableSeatIds;

    public SeatsUnavailableException(List<UUID> unavailableSeatIds) {
        super("Seats unavailable: " + unavailableSeatIds);
        this.unavailableSeatIds = unavailableSeatIds;
    }

    public List<UUID> getUnavailableSeatIds() {
        return unavailableSeatIds;
    }
}