# Architecture and reservation invariants

```mermaid
flowchart LR
  Browser["React web app"] -->|"same-origin HTTPS"| Edge["Nginx / load balancer"]
  Edge -->|"REST and SSE"| API["Spring Boot API"]
  API -->|"transactions and revisions"| DB[("PostgreSQL")]
  API -->|"rate counters"| Redis[("Redis")]
  Workers["Scheduled workers"] -->|"expiry, provider events, outbox"| DB
  Workers -->|"publish with acknowledgement"| Rabbit["RabbitMQ"]
  Rabbit -->|"at-least-once delivery"| Consumer["Inbox consumer"]
  Consumer -->|"idempotent insert"| DB
  API -->|"metrics"| Metrics["Prometheus / Grafana"]
```

## Booking sequence

```mermaid
sequenceDiagram
  actor Customer
  participant Web
  participant API
  participant DB as PostgreSQL
  participant Provider as Razorpay
  Customer->>Web: Pick seats
  Web->>API: Create hold with idempotency key
  API->>DB: Lock account and requested inventory in seat order
  API->>DB: Write hold, price snapshot, revision and outbox
  API-->>Web: Hold ID, server time and expiry
  Customer->>Web: Review booking and start payment
  alt SeatLock Pay demo
    Web->>API: Confirm demo payment for hold
  else Razorpay checkout
    Web->>API: Request provider order
    API->>Provider: Create order with server amount and currency
    Provider->>Web: Return checkout confirmation
    Web->>API: Submit signed payment proof
    API->>Provider: Fetch captured payment
  end
  API->>DB: Verify active expiry and ownership under locks
  API->>DB: Insert booking and unique tickets with SOLD inventory
  API-->>Web: Return booking reference or payment status
```

## Data model

```mermaid
erDiagram
    USERS ||--o| ORGANIZERS : applies
    USERS ||--o{ HOLDS : requests
    USERS ||--o{ BOOKINGS : owns
    USERS ||--o{ WAITLIST_ENTRIES : joins
    USERS ||--o{ ADMISSION_TOKENS : queues
    USERS ||--o{ IDEMPOTENCY_KEYS : retries
    ORGANIZERS ||--o{ VENUES : manages
    ORGANIZERS ||--o{ EVENTS : publishes
    VENUES ||--o{ SECTIONS : contains
    VENUES ||--o{ EVENTS : hosts
    SECTIONS ||--o{ SEATS : locates
    EVENTS ||--o{ EVENT_SEATS : inventories
    SEATS ||--o{ EVENT_SEATS : identifies
    EVENTS ||--o{ PRICING_TIERS : sets
    PRICING_TIERS ||--o{ EVENT_SEATS : prices
    HOLDS ||--o{ HOLD_ITEMS : records
    EVENT_SEATS ||--o{ HOLD_ITEMS : references
    HOLDS ||--o{ PAYMENTS : attempts
    PAYMENTS ||--o{ PROVIDER_EVENTS : emits
    HOLDS ||--o| BOOKINGS : converts
    BOOKINGS ||--o{ BOOKING_ITEMS : contains
    HOLD_ITEMS ||--o| BOOKING_ITEMS : snapshots
    BOOKING_ITEMS ||--|| TICKETS : issues
    TICKETS ||--o| TICKET_CHECKINS : validates
    WAITLIST_ENTRIES ||--o| HOLDS : offers
    OUTBOX_EVENTS ||--o{ PROCESSED_EVENTS : records
    OUTBOX_EVENTS ||--o| NOTIFICATIONS : delivers
    USERS ||--o{ AUDIT_LOGS : acts
```

## Concurrency decisions

- Each hold, provider fulfillment, expiration, inventory block and refund is a short transaction. Existing holds are locked before their seats. A customer lock serializes the account cap across disjoint requests. Seat locks are taken by ascending seat ID.
- A new hold can reclaim an expired seat without locking its former hold. A cleanup worker releases only rows still owned by the old hold and may mark the old hold expired even if it no longer owns any rows.
- The confirmation transaction reads the database clock after taking the locks. SeatLock Pay commits its demo payment and booking together. A Razorpay capture past expiry or without all the seats queues an idempotent refund. A processed refund voids tickets in the same transaction that restores inventory. Check-in and refund serialize on ticket rows.
- Payment events, idempotency scopes, hold-to-booking identity, check-ins and active ticket ownership have database uniqueness constraints. Idempotency replay returns the existing resource with its current status.
- Each inventory change increments the event revision before commit. The SSE channel sends event ID and revision. Every update and reconnect triggers a full snapshot. Fallback polling handles network loss and lazy-expiry changes.
- Waiting room admission is a two-minute lease tied to the authenticated account and event. Joining again while queued keeps the same position. Rejoining after expiry goes to the back. Waitlists reserve available inventory for the oldest eligible group, give preference to wheelchair inventory only when requested, and do not promise adjacent seats. A group at the head can delay smaller groups.
- Outbox claims use `SKIP LOCKED`. A publisher acknowledgement and a separate database commit can produce redelivery after process loss. The notification transaction inserts its consumer key and derived notification together. The broker is outside the seat mutation transaction.

[PostgreSQL 16 row lock documentation](https://www.postgresql.org/docs/16/explicit-locking.html)
