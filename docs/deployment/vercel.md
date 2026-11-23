# Deploy the SeatLock demo on Vercel

The repository uses Vercel Services for Vite and the Java 21 API. Vercel serves the React build and routes `/api/` and `/actuator/health` to the Spring Boot container. [Vercel Services configuration](https://vercel.com/docs/services/config-reference)

Link the repository root to the Vercel project. Connect a dedicated Neon database in the same region through the Vercel Marketplace. The `vercel` Spring profile reads `PGHOST_UNPOOLED`, `PGDATABASE`, `PGUSER`, and `PGPASSWORD` from the project's environment. Flyway applies the booking schema at startup.

Set `SPRING_PROFILES_ACTIVE=vercel` and `PORT=8181` in the deployment environment. The profile seeds demo events and enables SeatLock Pay for them. Public users can start a demo guest session. The built-in staff sign-ins are reserved for local use.

Verify the deployed health endpoint and complete a demo checkout through the receipt and QR ticket. Use a dedicated database for this demo. The API uses PostgreSQL for reservations, sessions, and its outbox. Scheduled work runs while the API container is active.
