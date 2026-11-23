# PostgreSQL owns inventory

The original repository contained one README and no implementation. SeatLock uses a Spring Boot modular monolith and a React client. Scheduled jobs run in the API process and can also run in a dedicated worker process. PostgreSQL coordinates both deployments with row locks and uniqueness constraints.

Hold creation locks the customer and requested inventory in ascending seat ID order. It checks wall-clock time after acquiring locks and commits the hold, its seats, and the availability event in one transaction. Checkout, cancellation, and expiration lock the existing hold before locking its seats. A fresh hold can take an expired inventory row without locking the previous hold. Expiration releases only rows that still carry that hold's ID.

The same event seat can appear in historical booking items. A partial unique index permits one non-void ticket per event seat. Payment provider events are durable and fulfillment is a separate transaction. A late Razorpay capture queues an idempotent refund if the hold expired or the inventory moved.

Each inventory transaction increments one event revision as its final write to the event row. The SSE bridge reads revisions from PostgreSQL and tells clients to fetch a snapshot. RabbitMQ processes notifications from a transactional outbox, and Redis can accelerate rate limiting. Both are outside the inventory transaction.
