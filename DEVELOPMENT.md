# Development

## Compose profiles

```sh
docker compose up --build --wait
docker compose --profile monitoring up -d
SEATLOCK_INLINE_WORKERS=false docker compose --profile worker up -d --build
```

The monitoring profile serves [Grafana](http://127.0.0.1:3017) and [Prometheus](http://127.0.0.1:9097) on loopback. The worker profile separates RabbitMQ consumers and scheduled jobs from the public API process. Use `docker compose logs -f api worker` to follow their output. To stop the local stack, run `docker compose down`. Its database and broker volumes are preserved.

## Native development

Install Java 21, Maven 3.9+, Node.js 24 LTS, and PostgreSQL 16 or use the existing Compose dependencies with loopback port mappings. Create an empty PostgreSQL database and matching role. Flyway runs when the API starts. Set `SEATLOCK_SEED=true` on the first start for demo data.

```sh
export DATABASE_URL=jdbc:postgresql://127.0.0.1:5447/seatlock
export DATABASE_USER=seatlock
export DATABASE_PASSWORD=replace_with_your_local_database_password
export SEATLOCK_SECURE_COOKIE=false
export SEATLOCK_SEED=true
export SEATLOCK_DEMO_PAYMENTS=true
export SEATLOCK_API_DOCS=true
export RAZORPAY_TEST_ONLY=true
mvn -f services/api/pom.xml spring-boot:run
```

In another terminal, run `npm ci` and `npm run dev`. Vite serves [the development web app](http://127.0.0.1:5173) and forwards API calls to port 8181. Override `SEATLOCK_API_ORIGIN` when running the API on another port.

| Variable | Purpose |
| --- | --- |
| `DATABASE_URL`, `DATABASE_USER`, `DATABASE_PASSWORD` | PostgreSQL. Password is required. |
| `SEATLOCK_BIND_ADDRESS`, `PORT` | API bind address and port. Default is loopback on 8181. |
| `SEATLOCK_SEED` | Seed demo accounts and events. Default is false outside Compose. |
| `SEATLOCK_DEMO_STAFF` | Allow local sign-in as the built-in organizer and admin. The Vercel demo profile disables it. |
| `SEATLOCK_DEMO_PAYMENTS` | Enable built-in SeatLock Pay for seeded events. The sample environment enables it. |
| `SEATLOCK_SECURE_COOKIE` | HTTPS-only sessions. Default is true outside Compose. |
| `SEATLOCK_HOLD_SECONDS` | Hold duration. Default is 600. |
| `SEATLOCK_REDIS_ENABLED`, `REDIS_URL` | Optional shared request quota counter. |
| `SEATLOCK_RABBIT_ENABLED`, `AMQP_URL` | Optional broker. Enable both in Compose. Without AMQP, the relay delivers to the local inbox transactionally. |
| `SEATLOCK_WORKERS_ENABLED` | API process worker switch. The Compose override is `SEATLOCK_INLINE_WORKERS`. |
| `SEATLOCK_ADMIN_EMAIL`, `SEATLOCK_ADMIN_PASSWORD` | Optional first administrator. Set both in a private environment for bootstrapping. Rotate the saved password through your operational procedure and remove the startup secret. |
| `SEATLOCK_API_DOCS` | Serve OpenAPI at `/v3/api-docs` when true. |
| `SEATLOCK_PROMETHEUS_OPEN` | Allow unauthenticated scrapes on the private API listener. Keep the listener off the public edge. |
| `RAZORPAY_TEST_ONLY` | Require Razorpay Test Mode keys. The local demo environment enables it. |
| `RAZORPAY_KEY_ID`, `RAZORPAY_KEY_SECRET`, `RAZORPAY_WEBHOOK_SECRET` | Server-side [Razorpay checkout configuration](docs/deployment/razorpay.md). |
| `TRACE_EXPORT_ENABLED`, `OTEL_EXPORTER_OTLP_TRACES_ENDPOINT` | Enable OTLP tracing to a reachable collector. |

## Troubleshooting

| Symptom | Check |
| --- | --- |
| The page is empty | Check `docker compose ps`, then `docker compose logs api` for the database and Flyway startup. |
| HTTP sign-in fails | Use `SEATLOCK_SECURE_COOKIE=false` for local HTTP. Use secure cookies with HTTPS deployment. |
| Port 8180 is occupied | Change `SEATLOCK_WEB_PORT` in the local environment and point Playwright at the new port. |
| Hold conflicts or an ended hold | Refresh the inventory. Reuse the same idempotency key when retrying the same request after a transport error. |
| Live updates reconnect frequently | Keep HTTP streaming enabled. The proxy must pass SSE without buffering. |
| Testcontainers cannot find Docker Desktop | On macOS, use `DOCKER_HOST=unix://$HOME/.docker/run/docker.sock TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock mvn -f services/api/pom.xml verify` if Docker Desktop has no default socket symlink. |
| No application metrics | Generate traffic and open Live Operations. The optional Grafana dashboard collects fresh samples on the next scrape. |

Use the default Testcontainers integration tests. If you supply `SEATLOCK_TEST_DATABASE_URL`, `SEATLOCK_TEST_DATABASE_USER`, and `SEATLOCK_TEST_DATABASE_PASSWORD`, the tests truncate SeatLock fixture tables in that database, so point them only at an isolated, disposable test database.
