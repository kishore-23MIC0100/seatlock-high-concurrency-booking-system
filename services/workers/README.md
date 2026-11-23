# SeatLock workers

The workers use the same JAR as the HTTP API and share its PostgreSQL schema. Set `SEATLOCK_INLINE_WORKERS=false` and run the Compose `worker` profile to move scheduled work and RabbitMQ consumers into a separate process. The worker has no published ports. It uses the same versioned booking code for provider callbacks and expiration.

Expiry and provider event handlers use PostgreSQL locks and repeatable delivery. The outbox relay waits for a RabbitMQ publisher acknowledgement. Inbox delivery uses a unique consumer/event key. Runbooks and failure modes are in the architecture notes.
