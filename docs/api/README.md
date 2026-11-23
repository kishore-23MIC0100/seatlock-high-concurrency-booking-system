# SeatLock API

The base path is `/api/v1`. Read the [generated OpenAPI document](openapi.json) or enable `SEATLOCK_API_DOCS=true` and open `/v3/api-docs`.

For a state-changing request, first get `/api/v1/auth/csrf`. Keep the session cookie and send the returned `token` using the returned `headerName`. Refresh the CSRF token after signing in. Session cookies are HTTP-only and same-site. Application routes are served from the same origin as the API.

| Workflow | Endpoints |
| --- | --- |
| Public inventory | `GET /events`, `GET /events/{id}`, `GET /events/{id}/seats`, `GET /events/{id}/availability/stream` |
| Account | `POST /auth/register`, `POST /auth/login`, `POST /auth/logout`, `GET /me`, `PATCH /me` |
| Reserve | `POST /holds`, `GET /holds/{id}`, `DELETE /holds/{id}`, `GET /me/holds` |
| Pay | `GET /holds/{id}/payment`, `POST /holds/{id}/payment/demo`, `POST /holds/{id}/payment/order`, `POST /holds/{id}/payment/verify`, `POST /holds/{id}/payment/sync`, `POST /holds/{id}/payment/free` |
| Self-service | `GET /me/bookings`, `GET /me/tickets`, `GET /me/tickets/{id}/qr`, `GET /me/tickets/{id}/download` |
| Queues | `POST /events/{id}/admission`, `GET /events/{id}/admission`, `GET /waitlist`, `POST /waitlist`, `DELETE /waitlist/{id}` |
| Staff | `/organizer/*`, `/admin/*`, `/ops/*` |

Include an `Idempotency-Key` header on `POST /holds`. Use a fresh key after changing the request. Retry the same key when the response was lost, the API returned a transient lock error, or the client timed out. A conflict includes a machine-readable code and may include the conflicting seat IDs in `details`.

The hold request supplies the event ID and numeric seat IDs. It returns the server quote in INR minor units, expiry, and authoritative clock. Create a Razorpay order from the reviewed hold. The server derives the charge from the stored quote. Provide the order ID, payment ID, and checkout signature to the verification endpoint. The server independently verifies capture and handles duplicate or late events. The webhook at `POST /payments/razorpay/webhook` authenticates with Razorpay’s signature.

For a seeded event in demo mode, payment status returns `mode: DEMO`. After review, the authenticated hold owner can complete SeatLock Pay with `POST /holds/{id}/payment/demo` and an empty body. The server records the quoted total, allocates tickets, and returns the booking ID. Repeated requests for the same hold return the same booking.

The SSE endpoint sends `inventory` events with an event ID and revision. Fetch the full seats snapshot after each signal, including the first message following reconnect.
