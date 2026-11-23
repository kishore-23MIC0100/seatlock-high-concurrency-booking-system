package com.seatlock.booking;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "seatlock.workers.enabled", havingValue = "true")
class ExpirationWorker {
    private final Reservations reservations;

    ExpirationWorker(Reservations reservations) {
        this.reservations = reservations;
    }

    @Scheduled(fixedRate = 1800)
    void releaseExpired() {
        for (int i = 0; i < 32 && reservations.expireNext(); i++) {
            // Each hold is processed in a separate transaction.
        }
    }
}
