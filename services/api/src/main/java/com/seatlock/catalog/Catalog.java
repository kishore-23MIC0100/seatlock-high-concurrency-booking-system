package com.seatlock.catalog;

import com.seatlock.booking.Reservations;
import com.seatlock.shared.ApiFault;
import com.seatlock.shared.Store;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class Catalog {
    public record Event(String id, String name, String description, String category, String artKey,
                        OffsetDateTime startsAt, OffsetDateTime endsAt, OffsetDateTime saleStartsAt,
                        UUID venueId, String venue, String city, String zoneId, int capacity,
                        long minPriceMinor, String status, Integer roomCapacity, boolean demo, long revision) {}
    public record Seat(long id, String section, String row, int number, boolean accessible, long priceMinor,
                       String tier, String state, long revision) {}
    public record Inventory(Event event, List<Seat> seats, OffsetDateTime serverTime, int holdSeconds,
                            long convenienceMinor, long platformMinor) {}
    private static final String EVENTS = """
        SELECT e.id,e.name,e.description,e.category,e.art_key,e.starts_at,e.ends_at,e.sale_starts_at,
            e.venue_id,v.name AS venue,v.city,v.zone_id,e.status,e.room_capacity,e.demo,e.revision,
            (SELECT count(*) FROM event_seats s WHERE s.event_id=e.id) AS capacity,
            coalesce((SELECT min(price_minor) FROM pricing_tiers t WHERE t.event_id=e.id),0) AS min_price_minor
        FROM events e JOIN venues v ON e.venue_id=v.id
        """;
    private final Store store;
    private final int holdSeconds;

    Catalog(Store store, @org.springframework.beans.factory.annotation.Value("${seatlock.hold-seconds}") int holdSeconds) {
        this.store = store;
        this.holdSeconds = holdSeconds;
        if (holdSeconds < 1 || holdSeconds > 3600) throw new IllegalArgumentException("Invalid hold duration");
    }

    public List<Event> events(String search, String category, String sort) {
        String order = switch (sort) {
            case "price" -> "min_price_minor, e.starts_at";
            case "latest" -> "e.starts_at DESC";
            default -> "e.starts_at";
        };
        return store.db.sql(EVENTS + """
            WHERE e.status='PUBLISHED' AND e.starts_at>clock_timestamp()
              AND (:category='' OR e.category=:category)
              AND (:search='' OR strpos(lower(concat_ws(' ',e.name,v.name,v.city,e.category,
                  to_char(e.starts_at AT TIME ZONE v.zone_id,'YYYY-MM-DD'))), :search)>0)
            ORDER BY
            """ + " " + order + ", e.id LIMIT 200")
            .param("search", search.strip().toLowerCase(Locale.ROOT))
            .param("category", category.toUpperCase(Locale.ROOT)).query(Event.class).list();
    }

    public Event event(String id) {
        return store.db.sql(EVENTS + " WHERE e.id=:id AND e.status='PUBLISHED'")
            .param("id", id).query(Event.class).optional().orElseThrow(() -> ApiFault.missing("Event not found."));
    }

    public Event ownedEvent(UUID organizer, String id) {
        return store.db.sql(EVENTS + " WHERE e.id=:id AND e.organizer_id=:organizer")
            .param("id", id).param("organizer", organizer).query(Event.class).optional()
            .orElseThrow(() -> ApiFault.missing("Event not found in your studio."));
    }

    public List<Event> organizerEvents(UUID organizer) {
        return store.db.sql(EVENTS + " WHERE e.organizer_id=:organizer ORDER BY e.starts_at DESC")
            .param("organizer", organizer).query(Event.class).list();
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Inventory inventory(String id) {
        Event event = event(id);
        OffsetDateTime now = store.now();
        return new Inventory(event, seats(id, now), now, holdSeconds,
            Reservations.CONVENIENCE_FEE, Reservations.PLATFORM_FEE);
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Inventory organizerInventory(UUID organizer, String id) {
        Event event = ownedEvent(organizer, id);
        OffsetDateTime now = store.now();
        return new Inventory(event, seats(id, now), now, holdSeconds,
            Reservations.CONVENIENCE_FEE, Reservations.PLATFORM_FEE);
    }

    private List<Seat> seats(String id, OffsetDateTime now) {
        return store.db.sql("""
            SELECT s.id,c.name AS section,s.row_label AS row,s.seat_number AS number,s.accessible,
                t.price_minor,t.label AS tier,es.revision,
                CASE WHEN es.state='HELD' AND es.expires_at<=:now THEN 'AVAILABLE' ELSE es.state END AS state
            FROM event_seats es JOIN seats s ON s.id=es.seat_id
              JOIN sections c ON c.id=s.section_id
              JOIN pricing_tiers t ON t.event_id=es.event_id AND t.id=es.pricing_tier_id
            WHERE es.event_id=:id ORDER BY c.id,s.row_label,s.seat_number
            """).param("id", id).param("now", now).query(Seat.class).list();
    }
}
