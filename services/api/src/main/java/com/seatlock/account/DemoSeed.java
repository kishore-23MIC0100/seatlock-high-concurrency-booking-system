package com.seatlock.account;

import com.seatlock.shared.Store;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
class DemoSeed implements ApplicationRunner {
    static final String ADMIN = "admin@demo.seatlock.test";
    static final String ORGANIZER = "organizer@demo.seatlock.test";
    private final Store store;
    private final Accounts accounts;
    private final boolean seed;
    private final String bootstrapEmail;
    private final String bootstrapPassword;

    DemoSeed(Store store, Accounts accounts, @Value("${seatlock.seed}") boolean seed,
             @Value("${seatlock.bootstrap-admin-email}") String bootstrapEmail,
             @Value("${seatlock.bootstrap-admin-password}") String bootstrapPassword) {
        this.store = store;
        this.accounts = accounts;
        this.seed = seed;
        this.bootstrapEmail = bootstrapEmail;
        this.bootstrapPassword = bootstrapPassword;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        store.db.sql("SELECT 1 FROM pg_advisory_xact_lock(741265)").query(Integer.class).single();
        if (!bootstrapEmail.isBlank() && accounts.byEmail(bootstrapEmail) == null) {
            accounts.create(bootstrapEmail, "SeatLock administrator", bootstrapPassword, "ADMIN");
        }
        if (!seed) return;
        if (accounts.byEmail(ADMIN) == null) accounts.create(ADMIN, "SeatLock Demo Admin", UUID.randomUUID().toString(), "ADMIN");
        if (accounts.byEmail(ORGANIZER) != null) return;
        UUID organizer = accounts.create(ORGANIZER, "Neon Live", UUID.randomUUID().toString(), "ORGANIZER").id();
        store.db.sql("INSERT INTO organizers(user_id,name,status) VALUES(:id,'Neon Live','APPROVED')")
            .param("id", organizer).update();
        UUID venue = store.db.sql("""
            INSERT INTO venues(organizer_id,name,city,zone_id)
            VALUES(:id,'Skyline Arena','Bengaluru','Asia/Kolkata') RETURNING id
            """).param("id", organizer).query(UUID.class).single();
        long section = store.db.sql("INSERT INTO sections(venue_id,name) VALUES(:venue,'Main Floor') RETURNING id")
            .param("venue", venue).query(Long.class).single();
        store.db.sql("""
            INSERT INTO seats(venue_id,section_id,row_label,seat_number,accessible)
            SELECT :venue,:section,chr(64+r),c,(r >= 7 AND c IN (1,24))
            FROM generate_series(1,8) r CROSS JOIN generate_series(1,24) c ORDER BY r,c
            """).param("venue", venue).param("section", section).update();
        seedEvent(organizer, venue, "neon-nights", "Neon Nights",
            "An electrifying live music experience with top indie and electronic artists.", "MUSIC", "neon", 0);
        seedEvent(organizer, venue, "afterglow-sessions", "Afterglow Sessions",
            "An intimate late-night set of ambient sounds and live electronic improvisation.", "MUSIC", "afterglow", 14);
        seedEvent(organizer, venue, "the-midnight-set", "The Midnight Set",
            "A lineup of Bengaluru comics takes the stage for an evening of stand-up.", "COMEDY", "midnight", 27);
    }

    private void seedEvent(UUID owner, UUID venue, String id, String name, String description, String category,
                           String art, int days) {
        ZoneId timeZone = ZoneId.of("Asia/Kolkata");
        var starts = LocalDate.of(LocalDate.now(timeZone).getYear() + 1, 2, 13)
            .plusDays(days).atTime(19, 0).atZone(timeZone).toOffsetDateTime();
        store.db.sql("""
            INSERT INTO events(id,organizer_id,venue_id,name,description,category,art_key,
                starts_at,ends_at,sale_starts_at,status,demo)
            VALUES(:id,:owner,:venue,:name,:description,:category,:art,:starts,:ends,
                clock_timestamp()-interval '1 day','PUBLISHED',true)
            """).param("id", id).param("owner", owner).param("venue", venue).param("name", name)
            .param("description", description).param("category", category).param("art", art)
            .param("starts", starts).param("ends", starts.plusHours(4)).update();
        long tier = store.db.sql("INSERT INTO pricing_tiers(event_id,label,price_minor) VALUES(:id,'Main Floor',199900) RETURNING id")
            .param("id", id).query(Long.class).single();
        long front = store.db.sql("INSERT INTO pricing_tiers(event_id,label,price_minor) VALUES(:id,'Front Row',249900) RETURNING id")
            .param("id", id).query(Long.class).single();
        store.db.sql("""
            INSERT INTO event_seats(event_id,venue_id,seat_id,pricing_tier_id,state)
            SELECT :id,venue_id,id,CASE WHEN row_label='A' THEN :front ELSE :tier END,
              CASE WHEN NOT accessible AND (row_label || seat_number) IN (
                'A8','A21','B8','B9','B10','B14','C4','C5','C12','C21','D5','D9','D12','D16',
                'E7','E14','E15','F4','F5','F10','F11','F15','F20','G3','G4','G11','G12','G17','G18','H20')
              THEN 'BLOCKED' ELSE 'AVAILABLE' END
            FROM seats WHERE venue_id=:venue
            """).param("id", id).param("tier", tier).param("front", front).param("venue", venue).update();
    }
}
