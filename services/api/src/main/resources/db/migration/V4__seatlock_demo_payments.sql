ALTER TABLE payments DROP CONSTRAINT payments_provider_check, DROP CONSTRAINT payments_provider_fields_check;
ALTER TABLE payments ADD CONSTRAINT payments_provider_check
    CHECK (provider IN ('LEGACY','RAZORPAY','FREE','SEATLOCK_DEMO'));
ALTER TABLE payments ADD CONSTRAINT payments_provider_fields_check CHECK (
    (provider='RAZORPAY' AND provider_order_id IS NOT NULL AND provider_payment_id IS NOT NULL AND scenario IS NULL)
    OR (provider='FREE' AND provider_order_id IS NULL AND provider_payment_id IS NULL AND amount_minor=0 AND scenario IS NULL)
    OR (provider='LEGACY' AND provider_order_id IS NULL AND provider_payment_id IS NULL AND scenario IS NOT NULL)
    OR (provider='SEATLOCK_DEMO' AND provider_order_id IS NULL AND provider_payment_id IS NULL AND amount_minor>0 AND scenario IS NULL)
);
CREATE UNIQUE INDEX payments_demo_per_hold ON payments(hold_id) WHERE provider='SEATLOCK_DEMO';
