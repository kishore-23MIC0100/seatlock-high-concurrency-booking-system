package com.seatlock.community;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "seatlock.workers.enabled", havingValue = "true")
class OutboxWorker {
    private final Outbox outbox;

    OutboxWorker(Outbox outbox) {
        this.outbox = outbox;
    }

    @Scheduled(fixedRate = 1700)
    void relay() {
        for (int i = 0; i < 32 && outbox.publishNext(); i++) {
            // Each published event has a separate claim and commit.
        }
    }
}
