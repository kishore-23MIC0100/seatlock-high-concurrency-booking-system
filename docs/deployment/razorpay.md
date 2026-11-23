# Razorpay checkout

SeatLock reviews the reservation first, then opens Razorpay Standard Checkout for the server-calculated INR total. Razorpay collects the payment details in its hosted form. SeatLock verifies the checkout signature and independently fetches the payment. A matching captured amount, currency, order, and active seat hold are required before a ticket can be issued. [Razorpay integration guide](https://razorpay.com/docs/payments/payment-gateway/web-integration/standard/integration-steps)

## Try hosted Razorpay checkout in Test Mode

The default local demo uses built-in SeatLock Pay. To exercise the hosted provider on the same demo events, follow these steps.

1. Switch the Razorpay Dashboard to **Test Mode**. Open **Account & Settings → API Keys** and generate a test key pair. [Razorpay Test Mode](https://razorpay.com/docs/payments/dashboard/test-live-modes)
2. Use `.env.example` as the template for a private `.env` file in the repository root. Set `SEATLOCK_DEMO_PAYMENTS=false` and keep `RAZORPAY_TEST_ONLY=true`. Set `RAZORPAY_KEY_ID` to your `rzp_test_…` key ID and `RAZORPAY_KEY_SECRET` to its matching secret. Enable automatic capture in Razorpay.
3. Run `docker compose up -d --build --wait`. Open [SeatLock](http://127.0.0.1:8180), select seats, sign in as a demo guest, and review the booking. Choose **Continue to Payment** and then **Pay … in Test Mode**. Use the test payment details linked on that screen. Successful capture issues a test booking, with the test amount labelled on its receipt. [Razorpay test payment details](https://razorpay.com/docs/payments/payment-gateway/web-integration/standard/integration-steps#2-test-integration)

On localhost, the browser callback and authenticated order checks verify capture. When testing webhook delivery, register a publicly reachable HTTPS endpoint and use a webhook secret. Use a separate demo database for test bookings. [Testing Razorpay webhooks](https://razorpay.com/docs/webhooks/validate-test)

## Configure live payments

1. Set `RAZORPAY_TEST_ONLY=false`, switch to an activated Razorpay account's Live Mode key pair, and set `RAZORPAY_WEBHOOK_SECRET` in your deployment's secrets. SeatLock provides the key ID to the hosted form. The key secret and webhook secret stay on the API server.
2. Enable automatic capture and register a public HTTPS webhook at `/api/v1/payments/razorpay/webhook` in the same Razorpay account and mode. Use the deployment's webhook secret and subscribe to `payment.captured`. [Razorpay webhook validation](https://razorpay.com/docs/webhooks/validate-test)
3. Use an HTTPS deployment with secure session cookies, `SEATLOCK_SEED=false` and `SEATLOCK_DEMO_PAYMENTS=false`. Set a private bootstrap administrator through `SEATLOCK_ADMIN_EMAIL` and `SEATLOCK_ADMIN_PASSWORD` if the database has no administrator. Live checkout rejects seeded events and disables demo sign-in and demo sessions. Publish a real event through an approved organizer account.
4. Validate the account's enabled payment methods and verification flow before accepting customer payments.

For a Compose restart after setting up the environment:

```sh
docker compose up -d --build --wait
```

## Reconciliation and refunds

A reservation uses one attached order. SeatLock uses its hold ID as the Razorpay receipt and looks up the receipt when retrying interrupted provisioning. Checkout verification, webhooks, and the reconciliation worker converge on the same payment and booking. The callback alone cannot issue a ticket. [Orders API](https://razorpay.com/docs/api/orders/create) · [Fetch Orders API](https://razorpay.com/docs/api/orders/fetch-all)

Checkout uses the remaining lease as its browser timeout. The database makes the final ownership and expiry decision. When a captured payment arrives after expiry or cancellation, SeatLock queues a full refund against that payment. Administrators can also request full pre-event refunds for eligible bookings. The job uses a stable Razorpay refund idempotency key, waits for `processed`, and then voids the booked tickets and releases the seats. A failed or repeatedly retried refund appears in Live Operations. [Razorpay idempotent refunds](https://hdfcbank-collectnow-docs.razorpay.com/api/refunds/normal-refunds-idempotent/)

Use SeatLock's refund workflow for bookings issued here. A refund or chargeback performed directly in the merchant dashboard requires manual reconciliation with SeatLock. Automated reconciliation polls short-lived orders for up to three days after the hold deadline, and signed capture webhooks can arrive later. Keep the original gateway mode and account available for resolving those orders and refunds.

Complimentary tickets use a separate zero-total confirmation action. Historical demo records are retained for booking history and excluded from live revenue reports.
