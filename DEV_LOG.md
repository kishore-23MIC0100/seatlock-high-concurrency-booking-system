# Development log

## 2026-11-23

| Milestone | Changes | Verification |
| --- | --- | --- |
| Baseline and design | Cloned the README-only repository. Added the Java and React monorepo, design foundation, and PostgreSQL architecture decision. | Verified the repository origin and local toolchain. |
| Inventory and payments | Added Flyway schema, row locking, idempotent holds, lazy and scheduled expiry, provider-event handling, tickets, and simulated refund. | Ran the Testcontainers PostgreSQL suite, including the 1,000-caller race. |
| Live customer journey | Built the Neon Nights seat map, discovery, account forms, checkout, receipts, QR tickets, notifications, and SSE reconciliation. | Exercised checkout, mobile map, keyboard control, and reconnect in Chromium. |
| Staff and asynchronous work | Added organizer publishing, check-in, admission and waitlist, admin review, outbox processing, and operations dashboards. | Exercised RabbitMQ inbox delivery locally and tested waitlist ordering, duplicate consumption, and scoped authorization. |
| Delivery | Added Compose, GitHub Actions, deployment template, generated OpenAPI, and test results. | See the current status and workload report for actual results. |
| Appearance and Razorpay | Added device-aware themes, booking motion, an adjacent seat finder, calendar downloads, review before payment, hosted checkout, capture verification, and refund reconciliation. | Verified theming and review in Chromium, and gateway boundary and inventory behavior in Java tests. Merchant verification requires credentials. |

Start the local demo with `cp .env.example .env` and `docker compose up --build --wait`. Run the suites listed in the README against a disposable demo stack.
