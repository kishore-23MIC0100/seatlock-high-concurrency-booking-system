package com.seatlock.community;

import com.seatlock.booking.Reservations;
import com.seatlock.shared.ApiFault;
import com.seatlock.shared.Store;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class Community {
    public record Entry(long id, String eventId, String eventName, int quantity, boolean needsAccessible,
                        String state, UUID offerHoldId, OffsetDateTime expiresAt, Long position) {}
    public record Admission(String status, Long position, OffsetDateTime expiresAt) {}
    public record Notice(long id, String message, OffsetDateTime createdAt, OffsetDateTime readAt) {}
    record EventGate(String status, OffsetDateTime saleStartsAt, OffsetDateTime startsAt, Integer roomCapacity) {}
    record Head(long id, UUID userId, int quantity, boolean needsAccessible) {}
    record Choice(long id, boolean accessible) {}
    private static final String ENTRIES = """
        SELECT w.id,w.event_id,e.name AS event_name,w.quantity,w.needs_accessible,w.state,w.offer_hold_id,
          h.expires_at,CASE WHEN w.state='QUEUED' THEN (SELECT count(*) FROM waitlist_entries q
            WHERE q.event_id=w.event_id AND q.state='QUEUED' AND q.id<=w.id) END AS position
        FROM waitlist_entries w JOIN events e ON e.id=w.event_id LEFT JOIN holds h ON h.id=w.offer_hold_id
        """;
    private final Store store;
    private final Reservations holds;

    Community(Store store, Reservations holds) {
        this.store = store;
        this.holds = holds;
    }

    public List<Entry> entries(UUID user) {
        return store.db.sql(ENTRIES + " WHERE w.user_id=:user ORDER BY w.id DESC LIMIT 200")
            .param("user", user).query(Entry.class).list();
    }

    @Transactional
    public Entry join(UUID user, String event, int quantity, boolean accessible) {
        store.boundLocks();
        serialize("waitlist:" + event);
        openEvent(event);
        var candidates = candidates(event, quantity, accessible);
        if (eligible(candidates, quantity, accessible)) {
            throw ApiFault.conflict("SEATS_AVAILABLE", "There are seats available for this group. Use the seat map.");
        }
        Long id = store.db.sql("""
            INSERT INTO waitlist_entries(event_id,user_id,quantity,needs_accessible)
            VALUES(:event,:user,:quantity,:accessible)
            ON CONFLICT (event_id,user_id) WHERE state IN ('QUEUED','OFFERED') DO NOTHING RETURNING id
            """).param("event", event).param("user", user).param("quantity", quantity).param("accessible", accessible)
            .query(Long.class).optional().orElseThrow(() -> ApiFault.conflict("WAITLIST_EXISTS", "You already have an entry for this event."));
        return entry(user, id);
    }

    public void leave(UUID user, long id) {
        Entry entry = entry(user, id);
        if (entry.state.equals("OFFERED")) {
            holds.cancel(user, entry.offerHoldId);
            return;
        }
        int changed = store.db.sql("UPDATE waitlist_entries SET state='LEFT' WHERE user_id=:user AND id=:id AND state='QUEUED'")
            .param("user", user).param("id", id).update();
        if (changed == 0 && entry.state.equals("QUEUED")) {
            throw ApiFault.conflict("WAITLIST_CHANGED", "Your waitlist entry changed. Reload and try again.");
        }
    }

    private Entry entry(UUID user, long id) {
        return store.db.sql(ENTRIES + " WHERE w.user_id=:user AND w.id=:id")
            .param("user", user).param("id", id).query(Entry.class).optional()
            .orElseThrow(() -> ApiFault.missing("Waitlist entry not found."));
    }

    List<String> queuedEvents() {
        return store.db.sql("""
            SELECT DISTINCT w.event_id FROM waitlist_entries w JOIN events e ON e.id=w.event_id
            WHERE w.state='QUEUED' AND e.status='PUBLISHED' AND e.sale_starts_at<=clock_timestamp()
              AND e.starts_at>clock_timestamp()
            """).query(String.class).list();
    }

    @Transactional
    public boolean offerNext(String event) {
        store.boundLocks();
        serialize("waitlist:" + event);
        var head = store.db.sql("""
            SELECT id,user_id,quantity,needs_accessible FROM waitlist_entries
            WHERE event_id=:event AND state='QUEUED' ORDER BY id LIMIT 1 FOR UPDATE
            """).param("event", event).query(Head.class).optional().orElse(null);
        if (head == null) return false;
        var candidates = candidates(event, head.quantity, head.needsAccessible);
        if (!eligible(candidates, head.quantity, head.needsAccessible)) return false;
        try {
            UUID hold = holds.allocateOffer(head.userId, event, candidates.stream().map(Choice::id).toList());
            store.db.sql("UPDATE waitlist_entries SET state='OFFERED',offer_hold_id=:hold WHERE id=:id")
                .param("hold", hold).param("id", head.id).update();
            return true;
        } catch (ApiFault fault) {
            if (fault.code.equals("SEAT_LIMIT") || fault.code.equals("FORBIDDEN")) {
                store.db.sql("UPDATE waitlist_entries SET state='EXPIRED' WHERE id=:id").param("id", head.id).update();
                return true;
            }
            if (fault.code.equals("SEAT_CONFLICT") || fault.code.equals("SALE_CLOSED")) return false;
            throw fault;
        }
    }

    private List<Choice> candidates(String event, int quantity, boolean accessible) {
        return store.db.sql("""
            SELECT s.id,s.accessible FROM event_seats es JOIN seats s ON s.id=es.seat_id
            WHERE es.event_id=:event AND (es.state='AVAILABLE' OR (es.state='HELD' AND es.expires_at<=clock_timestamp()))
            ORDER BY CASE WHEN :accessible THEN NOT s.accessible ELSE s.accessible END,s.id LIMIT :quantity
            """).param("event", event).param("quantity", quantity).param("accessible", accessible).query(Choice.class).list();
    }

    private static boolean eligible(List<Choice> choices, int quantity, boolean accessible) {
        return choices.size() == quantity && (!accessible || choices.stream().anyMatch(Choice::accessible));
    }

    public Admission admission(UUID user, String event) {
        EventGate gate = eventGate(event);
        if (gate.roomCapacity == null) return new Admission("ADMITTED", null, null);
        return store.db.sql("""
            SELECT CASE WHEN t.expires_at IS NULL THEN 'QUEUED' ELSE 'ADMITTED' END AS status,
                CASE WHEN t.expires_at IS NULL THEN (SELECT count(*) FROM admission_tokens q
                    WHERE q.event_id=t.event_id AND q.expires_at IS NULL AND q.id<=t.id) END AS position,t.expires_at
            FROM admission_tokens t WHERE t.event_id=:event AND t.user_id=:user
                AND (t.expires_at IS NULL OR t.expires_at>clock_timestamp())
            """).param("user", user).param("event", event).query(Admission.class).optional().orElse(new Admission("OPEN", null, null));
    }

    @Transactional
    public Admission enter(UUID user, String event) {
        store.boundLocks();
        serialize("admission:" + event);
        EventGate gate = openEvent(event);
        if (gate.roomCapacity == null) return new Admission("ADMITTED", null, null);
        store.db.sql("""
            INSERT INTO admission_tokens(event_id,user_id) VALUES(:event,:user)
            ON CONFLICT (event_id,user_id) DO UPDATE SET id=DEFAULT,expires_at=NULL,token_hash=NULL
            WHERE admission_tokens.expires_at<=clock_timestamp()
            """).param("event", event).param("user", user).update();
        admit(event, gate.roomCapacity);
        return admission(user, event);
    }

    List<String> rooms() {
        return store.db.sql("""
            SELECT id FROM events WHERE status='PUBLISHED' AND room_capacity IS NOT NULL
             AND sale_starts_at<=clock_timestamp() AND starts_at>clock_timestamp()
             AND EXISTS (SELECT 1 FROM admission_tokens WHERE event_id=events.id AND expires_at IS NULL)
            """).query(String.class).list();
    }

    @Transactional
    public void advanceRoom(String event) {
        store.boundLocks();
        serialize("admission:" + event);
        var gate = eventGate(event);
        if (gate.roomCapacity != null && gate.startsAt.isAfter(store.now())) admit(event, gate.roomCapacity);
    }

    private void admit(String event, int capacity) {
        Long active = store.db.sql("""
            SELECT count(*) FROM admission_tokens WHERE event_id=:event AND expires_at>clock_timestamp()
            """).param("event", event).query(Long.class).single();
        if (active >= capacity) return;
        store.db.sql("""
            UPDATE admission_tokens t SET expires_at=clock_timestamp()+interval '2 minutes',
               token_hash=encode(sha256(uuid_send(gen_random_uuid())),'hex')
            FROM (SELECT id FROM admission_tokens WHERE event_id=:event AND expires_at IS NULL
                   ORDER BY id LIMIT :limit) q WHERE q.id=t.id
            """).param("event", event).param("limit", capacity - active).update();
    }

    public List<Notice> notices(UUID user) {
        return store.db.sql("""
            SELECT id,message,created_at,read_at FROM notifications WHERE user_id=:user ORDER BY id DESC LIMIT 40
            """).param("user", user).query(Notice.class).list();
    }

    public void markRead(UUID user) {
        store.db.sql("UPDATE notifications SET read_at=clock_timestamp() WHERE user_id=:user AND read_at IS NULL")
            .param("user", user).update();
    }

    private void serialize(String key) {
        store.db.sql("SELECT 1 FROM pg_advisory_xact_lock(hashtextextended(:key,0))").param("key", key).query(Integer.class).single();
    }

    private EventGate openEvent(String event) {
        var gate = eventGate(event);
        var now = store.now();
        if (gate.saleStartsAt.isAfter(now) || !gate.startsAt.isAfter(now)) {
            throw ApiFault.conflict("SALE_CLOSED", "This event is outside its booking window.");
        }
        return gate;
    }

    private EventGate eventGate(String event) {
        return store.db.sql("""
            SELECT status,sale_starts_at,starts_at,room_capacity FROM events WHERE id=:event AND status='PUBLISHED'
            """).param("event", event).query(EventGate.class).optional().orElseThrow(() -> ApiFault.missing("Event not found."));
    }
}
