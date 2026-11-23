# Local verification

Verified on 23 November 2026 on an Apple M5 Mac with 16 GB RAM. The packaged stack ran under Docker Desktop using Java 21, PostgreSQL 16.15, Redis 7.4, RabbitMQ 4.2 and Nginx. Browser tests used Chromium through Playwright.

| Check | Result |
| --- | --- |
| Maven unit and PostgreSQL Testcontainers tests | 22 passed |
| 1,000 synchronized hold attempts on one seat | 1 hold, 999 expected conflicts, 1 ticket issued to the winner |
| Browser tests against the Compose web endpoint | 9 passed |
| TypeScript, ESLint, Vite production build | Passed |
| Production npm audit | 0 vulnerabilities reported by the registry at test time |
| Docker image builds and fresh stack | API and dependency checks passed. Nginx served health, REST, SPA routes and an SSE connection. |
| OpenAPI | 59 paths served and exported |
| RabbitMQ recovery | A booking confirmed while the broker was stopped. Its queued notification arrived once after the broker restarted. |

The Java tests also cover partial overlap and rollback, concurrent idempotency replay, the per-account cap, duplicate browser and webhook delivery, rejected proof and amount mismatches, order reuse, expired seat reassignment versus two workers, refund/reissue conflicts, repeat check-in, Redis loss, queue order, outbox replay, and PostgreSQL deadlock recovery. PostgreSQL used a 20-connection API pool, so the 1,000 tasks were synchronized callers and were not 1,000 concurrent database connections.

The browser suite covers system appearance, persistent overrides, reduced motion, the seat finder, SeatLock Pay from booking review through demo QR download, and complimentary checkout with calendar and QR downloads. The demo checkout tests cover concurrent submissions, hold ownership, expiry, and existing provider orders. The Razorpay tests exercise provider-shaped responses at the integration boundary. Hosted test and live transactions require keys for the relevant Razorpay mode.

## Historical k6 workload before Razorpay

This original run used the earlier demo-payment flow. The current load script exercises holds and releases without calling the payment provider. The test used eight virtual users for 15 seconds from the host to the Compose endpoint. Each user created a demo session before starting. Iterations mixed event reads and seat snapshots with attempts against a small pool of available seats, successful release, and occasional checkout. The script classifies only correctly formed seat conflicts as expected. This is a short local sample.

| Measured quantity | Result |
| --- | ---: |
| HTTP requests | 621 |
| HTTP requests per second, including setup time | 36.45 |
| Unexpected HTTP failures | 0 |
| Script checks | 597 passed |
| Hold attempts | 112 |
| Expected seat conflicts | 27 (24.11%) |
| Confirmed bookings issued by k6 | 3 |
| HTTP p50 / p95 / p99 | 14.93 / 28.39 / 215.07 ms |
| Hold p50 / p95 / p99 | 16.02 / 24.06 / 26.53 ms |

During the run a single Docker stats sample observed API CPU 53.88% and memory 404.1 MiB, PostgreSQL CPU 10.20% and memory 75.47 MiB, Redis CPU 0.59% and memory 9.53 MiB, RabbitMQ CPU 1.54% and memory 110.4 MiB, and web CPU 1.10% and memory 9.98 MiB. CPU values are Docker's per-container sample. They are not peaks or averages.

```sh
docker compose up --build --wait
mvn -B -f services/api/pom.xml verify
SEATLOCK_WEB_URL=http://127.0.0.1:8180 npm run test:e2e
k6 run tests/load/booking.js
```

The raw [k6 summary](k6-local-results.json) includes setup traffic. The 1,000-attempt invariant test and the HTTP load run exercise different workloads. Compare results only on matching data, hardware, and concurrency profiles.
