# SeatLock

**One Seat. One Owner. Zero Double Bookings.**

SeatLock is a full-stack ticket reservation system. Customers discover events, review reservations, pay through SeatLock Pay in the demo or Razorpay on a configured live deployment, and download QR tickets. Organizers publish events, manage inventory, and scan entries. Administrators handle organizer approvals, refunds, and audit history. Live Operations reads its metrics from PostgreSQL, RabbitMQ, and the API process.

## Run the demo

Requires Docker Desktop or a Docker Engine with Compose. The containers support Apple Silicon and x86-64.

```sh
cp .env.example .env
docker compose up --build --wait
```

Open [SeatLock on localhost](http://127.0.0.1:8180). Choose seats at Neon Nights, continue to checkout, and choose **Continue as demo guest**. Review the booking, click **Continue to Payment**, then use **Pay … in demo**. SeatLock Pay records the test payment and issues a receipt and demo QR tickets. The built-in checkout requires no gateway keys and moves no real money.

The signed-in hold has a ten-minute server countdown. Use Appearance to choose Light, Dark, or System. The seat finder can select adjacent seats by price, proximity, and wheelchair access. The Sign in page also offers **Organizer demo** and **Admin demo**. Docker publishes only the web port to the local host. For hosted checkout, see [Razorpay configuration](docs/deployment/razorpay.md).

## What to inspect

| Concern | Implementation |
| --- | --- |
| Atomic inventory | PostgreSQL transaction, deterministic seat locks, and rollback if any requested seat is unavailable |
| Duplicate retries | Request hash and result identity scoped by customer and operation |
| Late payment | Authenticated Razorpay capture verification, then locked verification of seat ownership and database time |
| Ticket ownership | Partial unique index on active tickets for each event seat, including checked-in tickets |
| Live state | Event revisions over SSE and a REST snapshot after updates or reconnect |
| Asynchronous work | PostgreSQL outbox, RabbitMQ publisher confirms and retries, idempotent inbox consumer |
| Support | Organizer inventory tools, Razorpay refunds, audit records, and live operational data |

## Verify

```sh
npm ci
npm run lint
npm run build
mvn -B -f services/api/pom.xml verify
npx playwright install chromium
SEATLOCK_WEB_URL=http://127.0.0.1:8180 npm run test:e2e
k6 run tests/load/booking.js
```

Run the browser and load suites against a disposable demo database started with `docker compose --env-file .env.example up --build --wait`. They create holds, receipts, accounts, and test events. The JUnit integration suite creates an isolated PostgreSQL 16.15 instance via Testcontainers.

- [Local development, ports, and configuration](DEVELOPMENT.md)
- [Architecture, data model, and concurrency walkthrough](docs/architecture/system.md)
- [API usage and OpenAPI contract](docs/api/README.md)
- [Verified results and remaining scope](PROJECT_STATUS.md)
- [Measured workload and reproduction](docs/testing/results.md)
- [AWS reference deployment](docs/deployment/aws.md)
- [Vercel demo deployment](docs/deployment/vercel.md)
- [Portfolio and interview walkthrough](docs/architecture/interview.md)
