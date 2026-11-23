package com.seatlock.catalog;

import com.seatlock.shared.ApiFault;
import com.seatlock.shared.Store;
import java.time.DateTimeException;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class Studio {
    record Venue(UUID id, String name, String city, String zoneId, int capacity) {}
    record VenueSeat(long id, String section, String row, int number, boolean accessible) {}
    record Report(String eventId, String eventName, int capacity, long bookings, long tickets, long amountMinor, long checkedIn) {}
    record Attendee(UUID ticketId, String eventName, String row, int number, String displayName, String state) {}
    private static final String VENUES = """
        SELECT v.id,v.name,v.city,v.zone_id,(SELECT count(*) FROM seats s WHERE s.venue_id=v.id) AS capacity
        FROM venues v WHERE v.organizer_id=:owner
        """;
    private final Store store;
    private final Catalog catalog;

    Studio(Store store, Catalog catalog) {
        this.store = store;
        this.catalog = catalog;
    }

    List<Venue> venues(UUID owner) {
        return store.db.sql(VENUES + " ORDER BY v.name,v.id").param("owner", owner).query(Venue.class).list();
    }

    Venue venue(UUID owner, UUID id) {
        return store.db.sql(VENUES + " AND v.id=:id").param("owner", owner).param("id", id)
            .query(Venue.class).optional().orElseThrow(() -> ApiFault.missing("Venue not found in your studio."));
    }

    List<VenueSeat> layout(UUID owner, UUID id) {
        venue(owner, id);
        return store.db.sql("""
            SELECT s.id,c.name AS section,s.row_label AS row,s.seat_number AS number,s.accessible
            FROM seats s JOIN sections c ON c.id=s.section_id WHERE s.venue_id=:id
            ORDER BY c.id,s.row_label,s.seat_number
            """).param("id", id).query(VenueSeat.class).list();
    }

    @Transactional
    public Venue createVenue(UUID owner, StudioController.NewVenue data) {
        String zone;
        try {
            zone = ZoneId.of(data.zoneId()).getId();
        } catch (DateTimeException invalid) {
            throw ApiFault.bad("Enter a valid time zone, such as Asia/Kolkata.");
        }
        var accessible = new HashSet<String>();
        for (String label : data.accessible()) {
            String value = label.strip().toUpperCase(Locale.ROOT);
            if (!value.matches("[A-Z][1-9][0-9]?") || value.charAt(0) - 'A' >= data.rows()
                    || Integer.parseInt(value.substring(1)) > data.seatsPerRow()) {
                throw ApiFault.bad("Accessible seat " + value + " is outside the venue layout.");
            }
            accessible.add(value);
        }
        UUID id = store.db.sql("""
            INSERT INTO venues(organizer_id,name,city,zone_id)
            VALUES(:owner,:name,:city,:zone) RETURNING id
            """).param("owner", owner).param("name", data.name().strip()).param("city", data.city().strip())
            .param("zone", zone).query(UUID.class).single();
        long section = store.db.sql("""
            INSERT INTO sections(venue_id,name) VALUES(:venue,:name) RETURNING id
            """).param("venue", id).param("name", data.section().strip()).query(Long.class).single();
        store.db.sql("""
            INSERT INTO seats(venue_id,section_id,row_label,seat_number)
            SELECT :venue,:section,chr(64+r),c FROM generate_series(1,:rows) r
            CROSS JOIN generate_series(1,:cols) c ORDER BY r,c
            """).param("venue", id).param("section", section).param("rows", data.rows())
            .param("cols", data.seatsPerRow()).update();
        if (!accessible.isEmpty()) {
            store.db.sql("""
                UPDATE seats SET accessible=true WHERE venue_id=:id AND (row_label || seat_number) IN (:labels)
                """).param("id", id).param("labels", accessible).update();
        }
        store.audit(owner, "VENUE_CREATED", id, null);
        return venue(owner, id);
    }

    @Transactional
    public Venue renameVenue(UUID owner, UUID id, StudioController.VenueMetadata data) {
        venue(owner, id);
        store.db.sql("UPDATE venues SET name=:name,city=:city WHERE id=:id")
            .param("name", data.name().strip()).param("city", data.city().strip()).param("id", id).update();
        store.audit(owner, "VENUE_UPDATED", id, null);
        return venue(owner, id);
    }

    @Transactional
    public Catalog.Event createEvent(UUID owner, StudioController.NewEvent data) {
        Venue venue = venue(owner, data.venueId());
        validateSchedule(data.saleStartsAt(), data.startsAt(), data.endsAt());
        int rows = store.db.sql("SELECT count(DISTINCT row_label) FROM seats WHERE venue_id=:id")
            .param("id", venue.id).query(Integer.class).single();
        if (data.premiumRows() > rows || (data.premiumRows() > 0 && data.premiumPriceMinor() == null)) {
            throw ApiFault.bad("Set a price and a valid number of premium rows.");
        }
        String slug = data.name().strip().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-").replaceAll("^-|-$", "");
        if (slug.isEmpty()) slug = "event";
        String id = slug.substring(0, Math.min(48, slug.length())) + "-" + UUID.randomUUID().toString().substring(0, 12);
        store.db.sql("""
            INSERT INTO events(id,organizer_id,venue_id,name,description,category,art_key,starts_at,ends_at,
              sale_starts_at,room_capacity)
            VALUES(:id,:owner,:venue,:name,:description,:category,'none',:start,:end,:sale,:room)
            """).param("id", id).param("owner", owner).param("venue", venue.id).param("name", data.name().strip())
            .param("description", data.description().strip()).param("category", data.category().name())
            .param("start", data.startsAt()).param("end", data.endsAt()).param("sale", data.saleStartsAt())
            .param("room", data.roomCapacity()).update();
        long standard = price(id, "Standard", data.priceMinor());
        long premium = data.premiumRows() > 0 ? price(id, "Premium", data.premiumPriceMinor()) : standard;
        store.db.sql("""
            INSERT INTO event_seats(event_id,venue_id,seat_id,pricing_tier_id)
            SELECT :id,venue_id,id,CASE WHEN ascii(row_label)-64<=:rows THEN :premium ELSE :standard END
            FROM seats WHERE venue_id=:venue
            """).param("id", id).param("rows", data.premiumRows()).param("premium", premium)
            .param("standard", standard).param("venue", venue.id).update();
        store.audit(owner, "EVENT_DRAFT_CREATED", id, null);
        return catalog.ownedEvent(owner, id);
    }

    private long price(String event, String label, long minor) {
        return store.db.sql("""
            INSERT INTO pricing_tiers(event_id,label,price_minor) VALUES(:event,:label,:price) RETURNING id
            """).param("event", event).param("label", label).param("price", minor).query(Long.class).single();
    }

    @Transactional
    public Catalog.Event updateDraft(UUID owner, String id, StudioController.DraftMetadata data) {
        catalog.ownedEvent(owner, id);
        validateSchedule(data.saleStartsAt(), data.startsAt(), data.endsAt());
        int changed = store.db.sql("""
            UPDATE events SET name=:name,description=:desc,sale_starts_at=:sale, starts_at=:start, ends_at=:end,room_capacity=:room
            WHERE id=:id AND organizer_id=:owner AND status='DRAFT'
            """).param("id", id).param("owner", owner).param("name", data.name().strip())
            .param("desc", data.description().strip()).param("sale", data.saleStartsAt()).param("start", data.startsAt())
            .param("end", data.endsAt()).param("room", data.roomCapacity()).update();
        if (changed == 0) throw ApiFault.conflict("EVENT_PUBLISHED", "Only drafts can be edited.");
        store.audit(owner, "EVENT_DRAFT_UPDATED", id, null);
        return catalog.ownedEvent(owner, id);
    }

    @Transactional
    public Catalog.Event publish(UUID owner, String id) {
        Catalog.Event event = catalog.ownedEvent(owner, id);
        if ("PUBLISHED".equals(event.status())) return event;
        int changed = store.db.sql("""
            UPDATE events SET status='PUBLISHED',revision=revision+1 WHERE id=:id AND organizer_id=:owner AND status='DRAFT'
            AND starts_at>clock_timestamp() AND EXISTS (
                SELECT 1 FROM event_seats WHERE event_id=:id AND state='AVAILABLE')
            """).param("id", id).param("owner", owner).update();
        if (changed == 0) throw ApiFault.conflict("PUBLISH_FAILED", "A future event with available seats is required.");
        store.audit(owner, "EVENT_PUBLISHED", id, null);
        store.outbox(id, "EVENT_PUBLISHED", null, owner);
        return catalog.ownedEvent(owner, id);
    }

    @Transactional
    public Catalog.Inventory block(UUID owner, String id, long seat, boolean blocked) {
        catalog.ownedEvent(owner, id);
        store.boundLocks();
        String target = blocked ? "BLOCKED" : "AVAILABLE";
        String previous = blocked ? "AVAILABLE" : "BLOCKED";
        int changed = store.db.sql("""
            UPDATE event_seats SET state=:next,revision=revision+1 WHERE event_id=:event AND seat_id=:seat AND state=:previous
            """).param("next", target).param("event", id).param("seat", seat).param("previous", previous).update();
        if (changed == 0) throw ApiFault.conflict("SEAT_IMMUTABLE", "Only available or blocked seats can be changed.");
        store.audit(owner, "SEAT_" + target, id + ":" + seat, null);
        store.inventoryChanged(id, "SEAT_" + target, null, owner);
        return catalog.organizerInventory(owner, id);
    }

    List<Report> reports(UUID owner) {
        return store.db.sql("""
            SELECT e.id AS event_id,e.name AS event_name,
              (SELECT count(*) FROM event_seats WHERE event_id=e.id) AS capacity,
              (SELECT count(*) FROM bookings WHERE event_id=e.id AND status='CONFIRMED') AS bookings,
              (SELECT count(*) FROM tickets WHERE event_id=e.id AND state<>'VOID') AS tickets,
              coalesce((SELECT sum(b.amount_minor) FROM bookings b JOIN payments p ON p.id=b.payment_id
                JOIN razorpay_orders o ON o.order_id=p.provider_order_id
                WHERE b.event_id=e.id AND b.status IN ('CONFIRMED','REFUND_PENDING') AND o.mode='LIVE'),0) AS amount_minor,
              (SELECT count(*) FROM tickets WHERE event_id=e.id AND state='CHECKED_IN') AS checked_in
            FROM events e WHERE e.organizer_id=:owner ORDER BY e.starts_at DESC
            """).param("owner", owner).query(Report.class).list();
    }

    List<Attendee> attendees(UUID owner) {
        return store.db.sql("""
            SELECT t.id AS ticket_id,e.name AS event_name,s.row_label AS row,s.seat_number AS number,
                u.display_name,t.state FROM tickets t JOIN events e ON e.id=t.event_id
            JOIN seats s ON s.id=t.seat_id JOIN booking_items i ON i.id=t.booking_item_id
            JOIN bookings b ON b.id=i.booking_id JOIN users u ON u.id=b.user_id
            WHERE e.organizer_id=:owner AND t.state<>'VOID' ORDER BY e.starts_at DESC,s.id LIMIT 300
            """).param("owner", owner).query(Attendee.class).list();
    }

    private void validateSchedule(OffsetDateTime sale, OffsetDateTime start, OffsetDateTime end) {
        if (!sale.isBefore(start) || !start.isBefore(end) || !start.isAfter(store.now())) {
            throw ApiFault.bad("Set a future start, with sales opening before it and the event ending after it.");
        }
    }
}
