package com.seatlock.community;

import io.micrometer.core.instrument.MeterRegistry;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "seatlock.workers.enabled", havingValue = "true")
class QueueWorker {
    private static final Logger log = LoggerFactory.getLogger(QueueWorker.class);
    private final Community community;
    private final MeterRegistry metrics;

    QueueWorker(Community community, MeterRegistry metrics) {
        this.community = community;
        this.metrics = metrics;
    }

    @Scheduled(fixedRate = 2500)
    void advance() {
        community.rooms().forEach(event -> run(event, community::advanceRoom));
        community.queuedEvents().forEach(event -> run(event, id -> {
            for (int i = 0; i < 8 && community.offerNext(id); i++) {
                // The oldest eligible request commits separately.
            }
        }));
    }

    private void run(String event, Consumer<String> job) {
        try {
            job.accept(event);
        } catch (RuntimeException error) {
            metrics.counter("seatlock.worker.failures", "worker", "queue").increment();
            log.warn("Queue advancement for {} failed ({})", event, error.getClass().getSimpleName());
        }
    }
}
