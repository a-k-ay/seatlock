package com.seatlock.holds;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface HoldRepository extends JpaRepository<Hold, UUID> {

    Optional<Hold> findBySeatIdAndStatus(UUID seatId, HoldStatus status);

    List<Hold> findByUserIdAndStatus(UUID userId, HoldStatus status);
}