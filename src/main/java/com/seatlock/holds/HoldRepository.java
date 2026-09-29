package com.seatlock.holds;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface HoldRepository extends JpaRepository<Hold, UUID> {

    Optional<Hold> findBySeatIdAndStatus(UUID seatId, HoldStatus status);

    List<Hold> findByUserIdAndStatus(UUID userId, HoldStatus status);

    List<Hold> findByStatusAndSeatIdIn(HoldStatus status, List<UUID> seatIds);

    List<Hold> findBySeatId(UUID seatId);

    @Modifying
    @Query(value = """
        WITH expiring AS (
            SELECT id FROM holds
            WHERE status = 'ACTIVE' AND expires_at < NOW()
            FOR UPDATE SKIP LOCKED
            LIMIT :batchSize
        )
        UPDATE holds
        SET status = 'EXPIRED', updated_at = NOW()
        WHERE id IN (SELECT id FROM expiring)
        """, nativeQuery = true)
    int expireDueHolds(@Param("batchSize") int batchSize);

}