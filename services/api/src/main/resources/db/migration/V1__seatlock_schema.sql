CREATE TABLE users (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    email text NOT NULL UNIQUE CHECK (email = lower(email)),
    display_name text NOT NULL,
    password_hash text NOT NULL,
    role text NOT NULL DEFAULT 'CUSTOMER' CHECK (role IN ('CUSTOMER','ORGANIZER','ADMIN')),
    enabled boolean NOT NULL DEFAULT true,
    created_at timestamptz NOT NULL DEFAULT clock_timestamp()
);

CREATE TABLE organizers (
    user_id uuid PRIMARY KEY REFERENCES users(id),
    name text NOT NULL,
    status text NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING','APPROVED','REJECTED')),
    created_at timestamptz NOT NULL DEFAULT clock_timestamp()
);

CREATE TABLE venues (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    organizer_id uuid NOT NULL REFERENCES organizers(user_id),
    name text NOT NULL,
    city text NOT NULL,
    zone_id text NOT NULL
);

CREATE TABLE sections (
    id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    venue_id uuid NOT NULL REFERENCES venues(id),
    name text NOT NULL,
    UNIQUE (venue_id, name),
    UNIQUE (venue_id, id)
);

CREATE TABLE seats (
    id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    venue_id uuid NOT NULL REFERENCES venues(id),
    section_id bigint NOT NULL,
    row_label text NOT NULL,
    seat_number integer NOT NULL CHECK (seat_number > 0),
    accessible boolean NOT NULL DEFAULT false,
    UNIQUE (section_id, row_label, seat_number),
    UNIQUE (venue_id, id),
    FOREIGN KEY (venue_id, section_id) REFERENCES sections(venue_id, id)
);

CREATE TABLE events (
    id text PRIMARY KEY,
    organizer_id uuid NOT NULL REFERENCES organizers(user_id),
    venue_id uuid NOT NULL REFERENCES venues(id),
    name text NOT NULL,
    description text NOT NULL,
    category text NOT NULL CHECK (category IN ('MUSIC','COMEDY','THEATRE','SPORTS')),
    art_key text NOT NULL DEFAULT 'neon',
    starts_at timestamptz NOT NULL,
    ends_at timestamptz NOT NULL,
    sale_starts_at timestamptz NOT NULL,
    status text NOT NULL DEFAULT 'DRAFT' CHECK (status IN ('DRAFT','PUBLISHED')),
    room_capacity integer CHECK (room_capacity > 0),
    demo boolean NOT NULL DEFAULT false,
    revision bigint NOT NULL DEFAULT 0,
    UNIQUE (id, venue_id),
    CHECK (sale_starts_at < starts_at AND starts_at < ends_at)
);
CREATE INDEX events_catalog ON events(starts_at, id) WHERE status = 'PUBLISHED';

CREATE TABLE pricing_tiers (
    event_id text NOT NULL REFERENCES events(id),
    id bigint GENERATED ALWAYS AS IDENTITY,
    label text NOT NULL,
    price_minor bigint NOT NULL CHECK (price_minor BETWEEN 0 AND 100000000),
    PRIMARY KEY (event_id, id),
    UNIQUE (event_id, label)
);

CREATE TABLE holds (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id uuid NOT NULL REFERENCES users(id),
    event_id text NOT NULL REFERENCES events(id),
    status text NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE','CONVERTED','EXPIRED','CANCELLED')),
    subtotal_minor bigint NOT NULL CHECK (subtotal_minor >= 0),
    convenience_minor bigint NOT NULL CHECK (convenience_minor >= 0),
    platform_minor bigint NOT NULL CHECK (platform_minor >= 0),
    expires_at timestamptz NOT NULL,
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    UNIQUE (event_id, id),
    UNIQUE (user_id, event_id, id)
);
CREATE INDEX holds_by_customer ON holds(user_id, event_id, expires_at) WHERE status = 'ACTIVE';
CREATE INDEX holds_due ON holds(expires_at, id) WHERE status = 'ACTIVE';

CREATE TABLE event_seats (
    event_id text NOT NULL,
    venue_id uuid NOT NULL,
    seat_id bigint NOT NULL,
    pricing_tier_id bigint NOT NULL,
    state text NOT NULL DEFAULT 'AVAILABLE' CHECK (state IN ('AVAILABLE','HELD','SOLD','BLOCKED')),
    hold_id uuid,
    expires_at timestamptz,
    revision bigint NOT NULL DEFAULT 0,
    PRIMARY KEY (event_id, seat_id),
    FOREIGN KEY (event_id, venue_id) REFERENCES events(id, venue_id),
    FOREIGN KEY (venue_id, seat_id) REFERENCES seats(venue_id, id),
    FOREIGN KEY (event_id, pricing_tier_id) REFERENCES pricing_tiers(event_id, id),
    FOREIGN KEY (event_id, hold_id) REFERENCES holds(event_id, id),
    CHECK ((state = 'HELD' AND hold_id IS NOT NULL AND expires_at IS NOT NULL)
        OR (state <> 'HELD' AND hold_id IS NULL AND expires_at IS NULL))
);
CREATE INDEX event_seats_by_hold ON event_seats(hold_id, seat_id) WHERE hold_id IS NOT NULL;

CREATE TABLE hold_items (
    hold_id uuid NOT NULL,
    event_id text NOT NULL,
    seat_id bigint NOT NULL,
    price_minor bigint NOT NULL CHECK (price_minor >= 0),
    PRIMARY KEY (hold_id, seat_id),
    UNIQUE (hold_id, event_id, seat_id),
    FOREIGN KEY (event_id, hold_id) REFERENCES holds(event_id, id),
    FOREIGN KEY (event_id, seat_id) REFERENCES event_seats(event_id, seat_id)
);

CREATE TABLE payments (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    hold_id uuid NOT NULL REFERENCES holds(id),
    status text NOT NULL DEFAULT 'PENDING'
        CHECK (status IN ('PENDING','APPROVED','DECLINED','TIMED_OUT','REVERSED','REFUNDED')),
    scenario text NOT NULL CHECK (scenario IN ('APPROVE','DECLINE','TIMEOUT','DELAYED','DUPLICATE','REORDERED')),
    amount_minor bigint NOT NULL CHECK (amount_minor >= 0),
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    UNIQUE (hold_id, id)
);
CREATE INDEX payments_by_hold ON payments(hold_id, created_at DESC);

CREATE TABLE provider_events (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    payment_id uuid NOT NULL REFERENCES payments(id),
    outcome text NOT NULL CHECK (outcome IN ('APPROVED','DECLINED','TIMED_OUT')),
    due_at timestamptz NOT NULL,
    processed_at timestamptz,
    attempts int NOT NULL DEFAULT 0 CHECK (attempts >= 0),
    failed_at timestamptz
);
CREATE INDEX provider_events_due ON provider_events(due_at, id)
    WHERE processed_at IS NULL AND failed_at IS NULL;

CREATE TABLE bookings (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id uuid NOT NULL REFERENCES users(id),
    event_id text NOT NULL REFERENCES events(id),
    hold_id uuid NOT NULL UNIQUE,
    payment_id uuid NOT NULL UNIQUE,
    status text NOT NULL DEFAULT 'CONFIRMED' CHECK (status IN ('CONFIRMED','REFUNDED')),
    amount_minor bigint NOT NULL CHECK (amount_minor >= 0),
    currency char(3) NOT NULL DEFAULT 'INR',
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    UNIQUE (id, event_id),
    UNIQUE (id, hold_id, event_id),
    FOREIGN KEY (user_id, event_id, hold_id) REFERENCES holds(user_id, event_id, id),
    FOREIGN KEY (hold_id, payment_id) REFERENCES payments(hold_id, id)
);
CREATE INDEX bookings_by_customer ON bookings(user_id, created_at DESC);

CREATE TABLE booking_items (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    booking_id uuid NOT NULL,
    hold_id uuid NOT NULL,
    event_id text NOT NULL,
    seat_id bigint NOT NULL,
    price_minor bigint NOT NULL CHECK (price_minor >= 0),
    UNIQUE (booking_id, seat_id),
    UNIQUE (id, event_id, seat_id),
    FOREIGN KEY (booking_id, hold_id, event_id) REFERENCES bookings(id, hold_id, event_id),
    FOREIGN KEY (hold_id, event_id, seat_id) REFERENCES hold_items(hold_id, event_id, seat_id)
);

CREATE TABLE tickets (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    booking_item_id uuid NOT NULL UNIQUE,
    event_id text NOT NULL,
    seat_id bigint NOT NULL,
    qr_token uuid NOT NULL UNIQUE DEFAULT gen_random_uuid(),
    state text NOT NULL DEFAULT 'VALID' CHECK (state IN ('VALID','CHECKED_IN','VOID')),
    FOREIGN KEY (booking_item_id, event_id, seat_id) REFERENCES booking_items(id, event_id, seat_id)
);
CREATE UNIQUE INDEX tickets_one_active_owner ON tickets(event_id, seat_id) WHERE state <> 'VOID';

CREATE TABLE ticket_checkins (
    ticket_id uuid PRIMARY KEY REFERENCES tickets(id),
    organizer_id uuid NOT NULL REFERENCES users(id),
    checked_in_at timestamptz NOT NULL DEFAULT clock_timestamp()
);

CREATE TABLE waitlist_entries (
    id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    event_id text NOT NULL REFERENCES events(id),
    user_id uuid NOT NULL REFERENCES users(id),
    quantity integer NOT NULL CHECK (quantity BETWEEN 1 AND 6),
    needs_accessible boolean NOT NULL,
    state text NOT NULL DEFAULT 'QUEUED' CHECK (state IN ('QUEUED','OFFERED','ACCEPTED','EXPIRED','LEFT')),
    offer_hold_id uuid UNIQUE,
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    FOREIGN KEY (user_id, event_id, offer_hold_id) REFERENCES holds(user_id, event_id, id),
    CHECK (state <> 'OFFERED' OR offer_hold_id IS NOT NULL)
);
CREATE UNIQUE INDEX waitlist_one_live_entry ON waitlist_entries(event_id, user_id) WHERE state IN ('QUEUED','OFFERED');
CREATE INDEX waitlist_head ON waitlist_entries(event_id,id) WHERE state = 'QUEUED';

CREATE TABLE admission_tokens (
    id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    event_id text NOT NULL REFERENCES events(id),
    user_id uuid NOT NULL REFERENCES users(id),
    token_hash text UNIQUE,
    expires_at timestamptz,
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    UNIQUE (event_id, user_id),
    CHECK ((expires_at IS NULL) = (token_hash IS NULL))
);
CREATE INDEX admission_order ON admission_tokens(event_id,id);

CREATE TABLE idempotency_keys (
    user_id uuid NOT NULL REFERENCES users(id),
    operation text NOT NULL,
    key text NOT NULL,
    request_hash text NOT NULL,
    result_id uuid,
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    PRIMARY KEY (user_id, operation, key)
);

CREATE TABLE outbox_events (
    id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    event_id text REFERENCES events(id),
    event_type text NOT NULL,
    subject_id uuid,
    user_id uuid REFERENCES users(id),
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    next_attempt_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    attempts integer NOT NULL DEFAULT 0,
    published_at timestamptz,
    failed_at timestamptz,
    last_error text
);
CREATE INDEX outbox_pending ON outbox_events(next_attempt_at, id) WHERE published_at IS NULL AND failed_at IS NULL;

CREATE TABLE processed_events (
    consumer text NOT NULL,
    event_id bigint NOT NULL REFERENCES outbox_events(id),
    processed_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    PRIMARY KEY (consumer, event_id)
);

CREATE TABLE notifications (
    id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    user_id uuid NOT NULL REFERENCES users(id),
    outbox_id bigint NOT NULL UNIQUE REFERENCES outbox_events(id),
    message text NOT NULL,
    read_at timestamptz,
    created_at timestamptz NOT NULL DEFAULT clock_timestamp()
);
CREATE INDEX notifications_inbox ON notifications(user_id,id DESC);

CREATE TABLE audit_logs (
    id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    actor_id uuid REFERENCES users(id),
    action text NOT NULL,
    target text NOT NULL,
    detail text,
    created_at timestamptz NOT NULL DEFAULT clock_timestamp()
);

CREATE TABLE spring_session (
    primary_id char(36) PRIMARY KEY,
    session_id char(36) NOT NULL UNIQUE,
    creation_time bigint NOT NULL,
    last_access_time bigint NOT NULL,
    max_inactive_interval int NOT NULL,
    expiry_time bigint NOT NULL,
    principal_name varchar(100)
);
CREATE INDEX spring_session_expiry ON spring_session(expiry_time);
CREATE INDEX spring_session_principal ON spring_session(principal_name);
CREATE TABLE spring_session_attributes (
    session_primary_id char(36) NOT NULL REFERENCES spring_session(primary_id) ON DELETE CASCADE,
    attribute_name varchar(200) NOT NULL,
    attribute_bytes bytea NOT NULL,
    PRIMARY KEY (session_primary_id, attribute_name)
);
