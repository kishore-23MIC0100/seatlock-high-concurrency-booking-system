# Security model

- BCrypt hashes passwords. The API rotates the session when signing in, stores session state in PostgreSQL, checks the account on authenticated requests, and uses CSRF tokens on cookie-authenticated mutations.
- Staff routes require a server-validated role. Each booking, hold, payment, ticket, venue, and event also has an ownership or scope check. Audit history records privileged actions and payment transitions.
- A ticket QR contains an opaque random token. The owning organizer checks the token online. A ticket row lock and a check-in primary key prevent repeat entry.
- Rate limits use Redis when reachable and bounded per-process counters during a connection outage. The edge and the PostgreSQL transaction enforce independent boundaries.
- Development-only privileged demo accounts require `SEATLOCK_SEED=true`. Production configuration uses private secrets, HTTPS cookies, and a private metrics listener. Razorpay collects payment details in its hosted checkout. SeatLock stores verified order, payment and refund references.
- The API verifies Razorpay signatures and remote capture details. It creates orders from the immutable server quote in integer minor units.
- Built-in SeatLock Pay requires both demo flags and a seeded demo event. It is disabled when a live gateway key is configured. It accepts an empty request body and records the server quote and demo booking in one database transaction. Demo tickets carry a demo label.
- Watch auth failures, busy responses, and worker failures in private operational logs. Avoid adding email addresses, QR tokens, cookies, credentials, or raw request bodies to logs.
