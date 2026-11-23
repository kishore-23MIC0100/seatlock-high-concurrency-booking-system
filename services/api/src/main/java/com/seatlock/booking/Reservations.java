package com.seatlock.booking;

import com.seatlock.shared.ApiFault;
import com.seatlock.shared.Store;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class Reservations {
    public static final int SEAT_LIMIT = 6;
    public static final long CONVENIENCE_FEE = 14900;
    public static final long PLATFORM_FEE = 5900;
    public record HeldSeat(long id, String row, int number, String section, long priceMinor) {}
    public record HoldView(UUID id, String eventId, String status, OffsetDateTime createdAt, OffsetDateTime expiresAt, OffsetDateTime serverTime,
                           long subtotalMinor, long convenienceMinor, long platformMinor, long totalMinor,
                           List<HeldSeat> seats, UUID bookingId) {}

    record Hold(UUID id, UUID userId, String eventId, String status, OffsetDateTime createdAt, OffsetDateTime expiresAt,
                long subtotalMinor, long convenienceMinor, long platformMinor) {
        boolean activeAt(OffsetDateTime now) {
            return status.equals("ACTIVE") && expiresAt.isAfter(now);
        }

        long total() {
            return subtotalMinor + convenienceMinor + platformMinor;
        }
    }
    record Sale(String status, OffsetDateTime saleStartsAt, OffsetDateTime startsAt, Integer roomCapacity) {}
    record LockedSeat(long seatId, String state, UUID holdId, OffsetDateTime expiresAt, long priceMinor) {
        boolean availableAt(OffsetDateTime now) {
            return state.equals("AVAILABLE") || (state.equals("HELD") && !expiresAt.isAfter(now));
        }
    }

    private static final String HOLD_FIELDS = """
        SELECT id,user_id,event_id,status,created_at,expires_at,subtotal_minor,convenience_minor,platform_minor FROM holds
        """;
    final Store store;
    private final Idempotency idempotency;
    private final int holdSeconds;
    private final MeterRegistry metrics;

    Reservations(Store store, Idempotency idempotency, @Value("${seatlock.hold-seconds}") int holdSeconds, MeterRegistry metrics) {
        this.store = store;
        this.idempotency = idempotency;
        this.holdSeconds = holdSeconds;
        this.metrics = metrics;
    }

    @Transactional
    public HoldView create(UUID user, String event, List<Long> requested, String key) {
        metrics.counter("seatlock.hold.attempts").increment();
        Timer.Sample measurement = Timer.start(metrics);
        try {
            List<Long> seats = requested.stream().distinct().sorted().toList();
            if (requested.size() != seats.size() || seats.isEmpty() || seats.size() > SEAT_LIMIT) {
                throw ApiFault.bad("Choose between one and six different seats.");
            }
            store.boundLocks();
            UUID existing = idempotency.claim(user, "hold", key, event + ":" + seats);
            if (existing != null) return view(user, existing);
            Hold hold = allocate(user, event, seats, false);
            idempotency.complete(user, "hold", key, hold.id);
            metrics.counter("seatlock.hold.created").increment();
            return toView(hold);
        } catch (ApiFault fault) {
            if (fault.code.equals("SEAT_CONFLICT")) metrics.counter("seatlock.hold.conflicts").increment();
            throw fault;
        } finally {
            measurement.stop(metrics.timer("seatlock.hold.duration"));
        }
    }

    // The caller owns the transaction. The customer lock also enforces the cap across disjoint seat sets.
    private void lockCustomer(UUID user) {
        if (store.db.sql("SELECT id FROM users WHERE id=:user AND enabled FOR NO KEY UPDATE")
            .param("user", user).query(UUID.class).optional().isEmpty()) {
            throw ApiFault.forbidden("This account cannot reserve seats.");
        }
    }

    // Waitlist workers call this inside the same transaction that assigns the queue entry.
    public UUID allocateOffer(UUID user, String event, List<Long> seats) {
        return allocate(user, event, seats, true).id;
    }

    private Hold allocate(UUID user, String event, List<Long> ids, boolean fromWaitlist) {
        lockCustomer(user);
        List<LockedSeat> seats = lockSeats(event, ids);
        OffsetDateTime now = store.now();
        Sale sale = store.db.sql("SELECT status,sale_starts_at,starts_at,room_capacity FROM events WHERE id=:event")
            .param("event", event).query(Sale.class).optional().orElseThrow(() -> ApiFault.missing("Event not found."));
        if (!sale.status.equals("PUBLISHED") || now.isBefore(sale.saleStartsAt) || !now.isBefore(sale.startsAt)) {
            throw ApiFault.conflict("SALE_CLOSED", "Seats are outside the sale window.");
        }
        if (!fromWaitlist && sale.roomCapacity != null && !admitted(user, event, now)) {
            throw ApiFault.conflict("ADMISSION_REQUIRED", "Join the waiting room for this event before reserving.");
        }
        if (seats.size() != ids.size()) throw ApiFault.bad("Some seats do not belong to this event.");
        List<Long> conflicts = seats.stream().filter(seat -> !seat.availableAt(now)).map(LockedSeat::seatId).toList();
        if (!conflicts.isEmpty()) {
            throw new ApiFault(HttpStatus.CONFLICT, "SEAT_CONFLICT", "Some seats are no longer available.",
                Map.of("seatIds", conflicts));
        }
        long already = store.db.sql("""
            SELECT
                (SELECT count(*) FROM hold_items i JOIN holds h ON h.id=i.hold_id
                 WHERE h.user_id=:user AND h.event_id=:event AND h.status='ACTIVE' AND h.expires_at>:now)
              + (SELECT count(*) FROM booking_items i JOIN bookings b ON b.id=i.booking_id
                 WHERE b.user_id=:user AND b.event_id=:event AND b.status IN ('CONFIRMED','REFUND_PENDING'))
            """).param("user", user).param("event", event).param("now", now).query(Long.class).single();
        if (already + seats.size() > SEAT_LIMIT) {
            throw ApiFault.conflict("SEAT_LIMIT", "This event allows six held or booked seats per account.");
        }
        long subtotal = seats.stream().mapToLong(LockedSeat::priceMinor).sum();
        long convenience = subtotal == 0 ? 0 : CONVENIENCE_FEE;
        long platform = subtotal == 0 ? 0 : PLATFORM_FEE;
        OffsetDateTime expiry = now.plusSeconds(holdSeconds);
        if (sale.startsAt.isBefore(expiry)) expiry = sale.startsAt;
        UUID hold = store.db.sql("""
            INSERT INTO holds(user_id,event_id,subtotal_minor,convenience_minor,platform_minor,created_at,expires_at)
            VALUES(:user,:event,:subtotal,:convenience,:platform,:created,:expires) RETURNING id
            """).param("user", user).param("event", event).param("subtotal", subtotal)
            .param("convenience", convenience).param("platform", platform).param("created", now).param("expires", expiry)
            .query(UUID.class).single();
        store.db.sql("""
            INSERT INTO hold_items(hold_id,event_id,seat_id,price_minor)
            SELECT :hold,s.event_id,s.seat_id,t.price_minor FROM event_seats s
            JOIN pricing_tiers t ON t.event_id=s.event_id AND t.id=s.pricing_tier_id
            WHERE s.event_id=:event AND s.seat_id IN (:ids)
            """).param("hold", hold).param("event", event).param("ids", ids).update();
        int updated = store.db.sql("""
            UPDATE event_seats SET state='HELD',hold_id=:hold,expires_at=:expires,revision=revision+1
            WHERE event_id=:event AND seat_id IN (:ids)
            """).param("hold", hold).param("expires", expiry).param("event", event).param("ids", ids).update();
        if (updated != seats.size()) throw new IllegalStateException("Hold and inventory row counts diverged");
        store.inventoryChanged(event, fromWaitlist ? "WAITLIST_OFFERED" : "HOLD_CREATED", hold, user);
        return new Hold(hold, user, event, "ACTIVE", now, expiry, subtotal, convenience, platform);
    }

    private boolean admitted(UUID user, String event, OffsetDateTime now) {
        return store.db.sql("""
            SELECT EXISTS(SELECT 1 FROM admission_tokens
              WHERE event_id=:event AND user_id=:user AND expires_at>:now)
            """).param("user", user).param("event", event).param("now", now).query(Boolean.class).single();
    }

    List<LockedSeat> lockSeats(String event, List<Long> ids) {
        return store.db.sql("""
            SELECT es.seat_id,es.state,es.hold_id,es.expires_at,t.price_minor
            FROM event_seats es JOIN pricing_tiers t ON t.event_id=es.event_id AND t.id=es.pricing_tier_id
            WHERE es.event_id=:event AND es.seat_id IN (:ids)
            ORDER BY es.seat_id FOR UPDATE OF es
            """).param("event", event).param("ids", ids).query(LockedSeat.class).list();
    }

    Hold ownHold(UUID user, UUID id, boolean lock) {
        return store.db.sql(HOLD_FIELDS + " WHERE id=:id AND user_id=:user" + (lock ? " FOR UPDATE" : ""))
            .param("id", id).param("user", user).query(Hold.class).optional()
            .orElseThrow(() -> ApiFault.missing("Reservation not found."));
    }

    Hold lockHold(UUID id) {
        return store.db.sql(HOLD_FIELDS + " WHERE id=:id FOR UPDATE").param("id", id).query(Hold.class).single();
    }

    @Transactional(readOnly = true, isolation = org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
    public HoldView view(UUID user, UUID id) {
        return toView(ownHold(user, id, false));
    }

    @Transactional(readOnly = true)
    public List<HoldView> active(UUID user) {
        return store.db.sql(HOLD_FIELDS + """
            WHERE user_id=:user AND status='ACTIVE' AND expires_at>clock_timestamp()
            ORDER BY expires_at LIMIT 50
            """).param("user", user).query(Hold.class).list().stream().map(this::toView).toList();
    }

    HoldView toView(Hold hold) {
        var now = store.now();
        String status = hold.status.equals("ACTIVE") && !hold.activeAt(now) ? "EXPIRED" : hold.status;
        List<HeldSeat> seats = store.db.sql("""
            SELECT s.id,s.row_label AS row,s.seat_number AS number,c.name AS section,i.price_minor
            FROM hold_items i JOIN seats s ON s.id=i.seat_id JOIN sections c ON c.id=s.section_id
            WHERE i.hold_id=:id ORDER BY c.id,s.row_label,s.seat_number
            """).param("id", hold.id).query(HeldSeat.class).list();
        UUID booking = store.db.sql("SELECT id FROM bookings WHERE hold_id=:id")
            .param("id", hold.id).query(UUID.class).optional().orElse(null);
        return new HoldView(hold.id, hold.eventId, status, hold.createdAt, hold.expiresAt, now, hold.subtotalMinor,
            hold.convenienceMinor, hold.platformMinor, hold.total(), seats, booking);
    }

    @Transactional
    public HoldView cancel(UUID user, UUID id) {
        store.boundLocks();
        Hold hold = ownHold(user, id, true);
        if (hold.status.equals("CONVERTED")) {
            throw ApiFault.conflict("ALREADY_BOOKED", "This reservation has become a booking.");
        }
        if (hold.status.equals("ACTIVE")) {
            release(hold, hold.activeAt(store.now()) ? "CANCELLED" : "EXPIRED");
        }
        return toView(ownHold(user, id, false));
    }

    @Transactional
    public boolean expireNext() {
        store.boundLocks();
        Hold expired = store.db.sql(HOLD_FIELDS + """
            WHERE status='ACTIVE' AND expires_at<=clock_timestamp()
            ORDER BY expires_at,id LIMIT 1 FOR UPDATE SKIP LOCKED
            """).query(Hold.class).optional().orElse(null);
        if (expired == null) return false;
        release(expired, "EXPIRED");
        return true;
    }

    void release(Hold hold, String status) {
        store.db.sql("""
            SELECT seat_id FROM event_seats WHERE event_id=:event AND hold_id=:id
            ORDER BY seat_id FOR UPDATE
            """).param("event", hold.eventId).param("id", hold.id).query(Long.class).list();
        int released = store.db.sql("""
            UPDATE event_seats SET state='AVAILABLE',hold_id=NULL,expires_at=NULL,revision=revision+1
            WHERE event_id=:event AND hold_id=:id AND state='HELD'
            """).param("event", hold.eventId).param("id", hold.id).update();
        store.db.sql("UPDATE holds SET status=:status WHERE id=:id")
            .param("status", status).param("id", hold.id).update();
        String waitlist = status.equals("EXPIRED") ? "EXPIRED" : "LEFT";
        store.db.sql("UPDATE waitlist_entries SET state=:status WHERE offer_hold_id=:id AND state='OFFERED'")
            .param("status", waitlist).param("id", hold.id).update();
        String type = "HOLD_" + status;
        if (released > 0) store.inventoryChanged(hold.eventId, type, hold.id, hold.userId);
        else store.outbox(hold.eventId, type, hold.id, hold.userId);
        if (status.equals("EXPIRED")) metrics.counter("seatlock.hold.expired").increment();
    }

    List<Long> heldIds(UUID id) {
        return store.db.sql("SELECT seat_id FROM hold_items WHERE hold_id=:id ORDER BY seat_id")
            .param("id", id).query(Long.class).list();
    }
}
