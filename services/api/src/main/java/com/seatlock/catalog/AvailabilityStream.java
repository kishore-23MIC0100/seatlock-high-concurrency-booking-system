package com.seatlock.catalog;

import com.seatlock.shared.ApiFault;
import com.seatlock.shared.Store;
import io.micrometer.core.instrument.MeterRegistry;
import java.io.IOException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@Service
public class AvailabilityStream {
    record Subscription(String event, SseEmitter emitter) {}
    record Revision(String id, long revision) {}

    private final Map<Subscription, Long> subscriptions = new HashMap<>();
    private final Store store;
    private final MeterRegistry metrics;

    AvailabilityStream(Store store, MeterRegistry metrics) {
        this.store = store;
        this.metrics = metrics;
        metrics.gauge("seatlock.sse.connections", this, stream -> stream.count());
    }

    public synchronized SseEmitter connect(String event) {
        if (subscriptions.size() >= 250) {
            throw new ApiFault(HttpStatus.SERVICE_UNAVAILABLE, "STREAM_FULL", "Live capacity reached. Refresh to update availability.");
        }
        SseEmitter emitter = new SseEmitter(180_000L);
        Subscription subscription = new Subscription(event, emitter);
        subscriptions.put(subscription, -1L);
        emitter.onCompletion(() -> remove(subscription));
        emitter.onTimeout(() -> remove(subscription));
        emitter.onError(error -> remove(subscription));
        metrics.counter("seatlock.sse.connects").increment();
        return emitter;
    }

    private synchronized int count() {
        return subscriptions.size();
    }

    private synchronized void remove(Subscription subscription) {
        subscriptions.remove(subscription);
    }

    @Scheduled(fixedRate = 1200)
    void notifyClients() {
        Map<Subscription, Long> current;
        synchronized (this) {
            current = Map.copyOf(subscriptions);
        }
        if (current.isEmpty()) return;
        Set<String> ids = new HashSet<>();
        current.keySet().forEach(subscription -> ids.add(subscription.event));
        Map<String, Long> revisions = new HashMap<>();
        store.db.sql("SELECT id,revision FROM events WHERE id IN (:ids) AND status='PUBLISHED'")
            .param("ids", ids).query(Revision.class).list().forEach(row -> revisions.put(row.id(), row.revision()));
        boolean heartbeat = System.currentTimeMillis() / 1000 % 12 < 2;
        current.forEach((subscription, last) -> {
            Long revision = revisions.get(subscription.event);
            if (revision == null) {
                subscription.emitter.complete();
                remove(subscription);
                return;
            }
            try {
                if (!revision.equals(last)) {
                    subscription.emitter.send(SseEmitter.event().name("inventory").id(revision.toString())
                        .data(Map.of("eventId", subscription.event, "version", revision)));
                    synchronized (this) {
                        subscriptions.replace(subscription, revision);
                    }
                } else if (heartbeat) {
                    subscription.emitter.send(SseEmitter.event().comment("alive"));
                }
            } catch (IOException | IllegalStateException disconnected) {
                remove(subscription);
            }
        });
    }
}
