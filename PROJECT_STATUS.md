# Project status

SeatLock runs with Java 21, Spring Boot 4.1, React, and PostgreSQL. The local stack also uses Redis and RabbitMQ. SeatLock Pay completes test bookings for seeded events. Razorpay is available for separately configured hosted payments.

| Area | Implemented scope |
| --- | --- |
| Customer | Discovery and filters, session-based account, keyboard and touch seat map, atomic holds, review, SeatLock Pay, Razorpay checkout and reconciliation, receipts, QR downloads, history, waitlist offers |
| Organizer | Customer application and admin approval, single-section grid layout, accessible seats, draft and publish, Standard and Premium tiers, blocked inventory, ticket check-in, live sales and CSV export |
| Administration | Account enable or disable, organizer decisions, bookings and payment history, Razorpay pre-event refunds, audit logs |
| Operations | Active reservations, queue depths and failures, recent HTTP latency, HTTP response counts, contention counters, database pool use, Prometheus metrics |
| Delivery | Flyway migrations, Compose, CI workflow, OpenAPI, AWS ECS task template, screenshots and verification results |

## Verified locally

- PostgreSQL 16.15 Testcontainers integration and Java unit tests.
- Desktop and mobile Chromium flows, including system themes, reduced motion, adjacent seat suggestions, the two-browser inventory update, and permission checks.
- RabbitMQ delivery of booking notifications, promoted waitlist offers, and Redis fallback during a connection outage.
- A fresh Docker Compose stack, including a successful checkout during a RabbitMQ outage and subsequent inbox delivery once the broker recovered.
- A measured local k6 workload. See [test results](docs/testing/results.md) for scope and numbers.

## Remaining scope

- Email delivery, password reset, automated chargeback handling, and a configurable per-event refund policy.
- Rich venue editing with multiple sections, aisle geometry, and published layout versioning. Current organizers generate a fixed-seat grid and can edit venue metadata and event availability.
- Camera scanning in the browser. The organizer check-in panel accepts the decoded payload from a QR scanner or scanning app.
- Automated incident remediation and a shared multi-instance latency view. The operations panel shows one API instance's recent observations. Prometheus aggregation can combine multiple private scrapes.
- Merchant activation and a live Razorpay transaction, AWS provisioning, hosted CI execution, and a production-scale benchmark. The repository includes deployment and CI configuration.

See the [security model](docs/security/model.md) and the [concurrency walkthrough](docs/architecture/system.md).
