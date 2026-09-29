package com.seatlock.holds;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@RequiredArgsConstructor
@Slf4j
public class HoldExpiryWorker {

    private static final int BATCH_SIZE = 100;

    private final HoldRepository holdRepository;

    @Scheduled(fixedDelayString = "30000")
    @Transactional
    public void expireStaleHolds() {
        int expired = holdRepository.expireDueHolds(BATCH_SIZE);
        if (expired > 0) {
            log.info("Hold expiry worker: expired {} hold(s)", expired);
        }
    }
}