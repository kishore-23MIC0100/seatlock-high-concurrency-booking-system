package com.seatlock.ops;

import com.seatlock.booking.Tickets.AuditEntry;
import com.seatlock.community.Outbox;
import com.seatlock.shared.Store;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/ops")
class Operations {
    record Counts(long activeHolds, long expiredHolds, long validTickets, long waitlistEntries,
                  long roomWaiting, long outboxPending, long outboxFailed, long paymentPending, long paymentFailed,
                  Double oldestOutboxSeconds, Double oldestOverdueHoldSeconds) {}
    record OpsStatus(String service, Counts inventory, long attempts, long conflicts, double workerFailures,
                     Integer databaseActive, Integer databaseMaximum, Long queueReady, Long queueDead,
                     RequestTelemetry.Window traffic, OffsetDateTime serverTime) {}
    record Reservation(UUID id, String eventId, String status, OffsetDateTime createdAt, OffsetDateTime expiresAt, long seats) {}
    record QueueEvent(long id, String type, int attempts, OffsetDateTime createdAt, OffsetDateTime nextAttemptAt, String lastError) {}
    record Incident(String id, String stage, String error, int attempts, OffsetDateTime failedAt) {}

    private final Store store;
    private final Outbox outbox;
    private final MeterRegistry metrics;
    private final RequestTelemetry telemetry;

    Operations(Store store, Outbox outbox, MeterRegistry metrics, RequestTelemetry telemetry) {
        this.store = store;
        this.outbox = outbox;
        this.metrics = metrics;
        this.telemetry = telemetry;
    }

    @GetMapping
    OpsStatus status() {
        Counts counts = store.db.sql("""
            SELECT
              (SELECT count(*) FROM holds WHERE status='ACTIVE' AND expires_at>clock_timestamp()) AS active_holds,
              (SELECT count(*) FROM holds WHERE status='EXPIRED') AS expired_holds,
              (SELECT count(*) FROM tickets WHERE state<>'VOID') AS valid_tickets,
              (SELECT count(*) FROM waitlist_entries WHERE state='QUEUED') AS waitlist_entries,
              (SELECT count(*) FROM admission_tokens WHERE expires_at IS NULL) AS room_waiting,
              (SELECT count(*) FROM outbox_events WHERE published_at IS NULL AND failed_at IS NULL) AS outbox_pending,
              (SELECT count(*) FROM outbox_events WHERE failed_at IS NOT NULL) AS outbox_failed,
              ((SELECT count(*) FROM razorpay_orders o JOIN holds h ON h.id=o.hold_id
                 WHERE h.status='ACTIVE' AND h.expires_at>clock_timestamp()
                   AND NOT EXISTS (SELECT 1 FROM payments p WHERE p.provider_order_id=o.order_id))
                + (SELECT count(*) FROM payment_refunds WHERE status='PENDING')) AS payment_pending,
              ((SELECT count(*) FROM payment_refunds WHERE status='FAILED' OR (status='PENDING' AND attempts>=5))
                + (SELECT count(*) FROM payments p WHERE p.status='REFUND_FAILED'
                     AND NOT EXISTS (SELECT 1 FROM payment_refunds r WHERE r.payment_id=p.id))) AS payment_failed,
              (SELECT extract(epoch FROM clock_timestamp()-min(created_at)) FROM outbox_events
               WHERE published_at IS NULL AND failed_at IS NULL) AS oldest_outbox_seconds,
              (SELECT extract(epoch FROM clock_timestamp()-min(expires_at)) FROM holds
               WHERE status='ACTIVE' AND expires_at<clock_timestamp()) AS oldest_overdue_hold_seconds
            """).query(Counts.class).single();
        double failures = metrics.find("seatlock.worker.failures").counters().stream().mapToDouble(c -> c.count()).sum();
        return new OpsStatus("UP", counts, counter("seatlock.hold.attempts"), counter("seatlock.hold.conflicts"), failures,
            gauge("hikaricp.connections.active"), gauge("hikaricp.connections.max"), outbox.queueDepth("seatlock.notifications"),
            outbox.queueDepth("seatlock.notifications.dead"), telemetry.window(), store.now());
    }

    @GetMapping("/reservations")
    List<Reservation> reservations() {
        return store.db.sql("""
            SELECT h.id,h.event_id,CASE WHEN h.status='ACTIVE' AND h.expires_at<=clock_timestamp() THEN 'EXPIRED'
                 ELSE h.status END AS status,h.created_at,h.expires_at,
                (SELECT count(*) FROM hold_items i WHERE i.hold_id=h.id) AS seats
            FROM holds h ORDER BY h.created_at DESC LIMIT 150
            """).query(Reservation.class).list();
    }

    @GetMapping("/queues")
    List<QueueEvent> queues() {
        return store.db.sql("""
            SELECT id,event_type AS type,attempts,created_at,next_attempt_at,last_error FROM outbox_events
            WHERE published_at IS NULL AND failed_at IS NULL ORDER BY next_attempt_at,id LIMIT 120
            """).query(QueueEvent.class).list();
    }

    @GetMapping("/latency")
    RequestTelemetry.Window latency() {
        return telemetry.window();
    }

    @GetMapping("/incidents")
    List<Incident> incidents() {
        return store.db.sql("""
            SELECT id::text,'OUTBOX' AS stage,last_error AS error,attempts,failed_at FROM outbox_events WHERE failed_at IS NOT NULL
            UNION ALL
            SELECT id::text,'RAZORPAY_REFUND',error_code,attempts,coalesce(completed_at,created_at)
            FROM payment_refunds WHERE status='FAILED' OR (status='PENDING' AND attempts>=5)
            UNION ALL
            SELECT p.id::text,'RAZORPAY_PAYMENT','REVIEW_REQUIRED',0,p.created_at FROM payments p WHERE p.status='REFUND_FAILED'
              AND NOT EXISTS (SELECT 1 FROM payment_refunds r WHERE r.payment_id=p.id)
            ORDER BY failed_at DESC LIMIT 120
            """).query(Incident.class).list();
    }

    @GetMapping("/logs")
    List<AuditEntry> logs() {
        return store.db.sql("SELECT id,actor_id,action,target,detail,created_at FROM audit_logs ORDER BY id DESC LIMIT 150")
            .query(AuditEntry.class).list();
    }

    private Long counter(String name) {
        var counter = metrics.find(name).counter();
        return counter == null ? 0L : (long) counter.count();
    }

    private Integer gauge(String name) {
        var gauge = metrics.find(name).gauge();
        return gauge == null || !Double.isFinite(gauge.value()) ? null : (int) gauge.value();
    }
}
