ALTER TABLE payments
    ADD COLUMN provider text NOT NULL DEFAULT 'LEGACY' CHECK (provider IN ('LEGACY','RAZORPAY','FREE')),
    ADD COLUMN provider_order_id text,
    ADD COLUMN provider_payment_id text UNIQUE,
    ALTER COLUMN scenario DROP NOT NULL,
    DROP CONSTRAINT payments_status_check;
ALTER TABLE payments ADD CONSTRAINT payments_status_check
    CHECK (status IN ('PENDING','APPROVED','DECLINED','TIMED_OUT','REVERSED','REFUND_PENDING','REFUND_FAILED','REFUNDED'));
ALTER TABLE payments ADD CONSTRAINT payments_provider_fields_check CHECK (
    (provider='RAZORPAY' AND provider_order_id IS NOT NULL AND provider_payment_id IS NOT NULL AND scenario IS NULL)
    OR (provider='FREE' AND provider_order_id IS NULL AND provider_payment_id IS NULL AND amount_minor=0 AND scenario IS NULL)
    OR (provider='LEGACY' AND provider_order_id IS NULL AND provider_payment_id IS NULL AND scenario IS NOT NULL)
);
UPDATE payments SET status='TIMED_OUT' WHERE provider='LEGACY' AND status='PENDING';
UPDATE provider_events SET processed_at=clock_timestamp() WHERE processed_at IS NULL AND failed_at IS NULL;

CREATE TABLE razorpay_orders (
    hold_id uuid PRIMARY KEY REFERENCES holds(id),
    amount_minor bigint NOT NULL CHECK (amount_minor >= 100),
    mode text NOT NULL CHECK (mode IN ('TEST','LIVE')),
    order_id text UNIQUE,
    provision_token uuid,
    provision_until timestamptz NOT NULL DEFAULT clock_timestamp(),
    reconcile_after timestamptz NOT NULL DEFAULT clock_timestamp(),
    UNIQUE (hold_id,order_id)
);
CREATE INDEX razorpay_orders_reconcile ON razorpay_orders(mode,reconcile_after) WHERE order_id IS NOT NULL;
ALTER TABLE payments ADD CONSTRAINT payments_razorpay_order_fk
    FOREIGN KEY (hold_id,provider_order_id) REFERENCES razorpay_orders(hold_id,order_id);
CREATE INDEX payments_razorpay_order ON payments(provider_order_id) WHERE provider_order_id IS NOT NULL;

ALTER TABLE bookings DROP CONSTRAINT bookings_status_check;
ALTER TABLE bookings ADD CONSTRAINT bookings_status_check CHECK (status IN ('CONFIRMED','REFUND_PENDING','REFUNDED'));

CREATE TABLE razorpay_webhooks (
    event_id text PRIMARY KEY,
    processed_at timestamptz NOT NULL DEFAULT clock_timestamp()
);

CREATE TABLE payment_refunds (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    payment_id uuid NOT NULL UNIQUE REFERENCES payments(id),
    provider_refund_id text UNIQUE,
    status text NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING','PROCESSED','FAILED')),
    attempts integer NOT NULL DEFAULT 0 CHECK (attempts >= 0),
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    next_attempt_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    completed_at timestamptz,
    error_code text
);
CREATE INDEX payment_refunds_pending ON payment_refunds(next_attempt_at,id) WHERE status='PENDING';
