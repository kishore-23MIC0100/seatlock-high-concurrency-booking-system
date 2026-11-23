# Portfolio and interview walkthrough

SeatLock is a full-stack, real-time ticketing project that uses database transactions to protect contested seats. The React customer flow is backed by a Java 21 and Spring Boot API, PostgreSQL Flyway migrations, Razorpay Standard Checkout, Redis rate counters, and RabbitMQ notifications. Independent workspaces support organizers, administrators, and live operations.

## Five-minute demo

1. Open Neon Nights in two browser contexts. Choose seats in one, continue as a demo customer, and confirm the second context sees the same seats become unavailable.
2. Review the booking and complete a test payment in SeatLock Pay. Show the payment reference on the receipt and download a demo QR ticket.
3. Open Organizer Studio and show event inventory, ticket check-in, and the real sales table.
4. Open Live Operations and show reservations, conflicts, and real recent API request latency.
5. Walk through the database test for 1,000 callers contending on one inventory row and the active-ticket uniqueness index.

## Design questions

**Where is the linearization point?** A successfully committed hold assigns all requested seats within the same short transaction. Competing transactions acquire the same row lock and see the resulting ownership before making a decision.

**What happens if the payment event arrives late?** The fulfillment transaction locks the original hold and all requested inventory. It checks database time and ownership and enqueues an idempotent Razorpay refund if the hold is no longer payable.

**What happens if the relay restarts after publishing?** The stored outbox row can be published again. The consumer's unique event marker and its notification commit together, so the user receives one inbox item.

**What limits throughput?** The inventory hotspot serializes successful ownership claims. A per-event revision update also serializes the tail of inventory writes. This simplification makes reconnect correctness understandable and is worth measuring before introducing an event partitioning scheme.
