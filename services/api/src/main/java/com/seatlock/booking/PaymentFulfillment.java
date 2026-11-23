package com.seatlock.booking;

import com.seatlock.shared.ApiFault;
import com.seatlock.shared.Store;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PaymentFulfillment {
    public record PaymentView(UUID id, UUID holdId, String status, String provider, String mode, String scenario,
                              long amountMinor, UUID bookingId, OffsetDateTime createdAt) {}
    record Attempt(UUID id, UUID holdId, String status, String provider, long amountMinor) {}

    private final Store store;
    private final Reservations holds;
    private final PaymentOrders orders;
    private final MeterRegistry metrics;

    PaymentFulfillment(Store store, Reservations holds, PaymentOrders orders, MeterRegistry metrics) {
        this.store = store;
        this.holds = holds;
        this.orders = orders;
        this.metrics = metrics;
    }

    @Transactional
    public void settleCapture(PaymentOrders.Order order, RazorpayClient.Payment payment, String webhookEvent) {
        store.boundLocks();
        if (!payment.settled() || !order.orderId().equals(payment.orderId()) || !"INR".equals(payment.currency())
                || payment.amount() != order.amountMinor() || payment.amountRefunded() < 0) {
            throw ApiFault.conflict("PAYMENT_MISMATCH", "The verified Razorpay payment does not match the reservation.");
        }
        if (webhookEvent != null) {
            int inserted = store.db.sql("""
                INSERT INTO razorpay_webhooks(event_id) VALUES(:id) ON CONFLICT DO NOTHING
                """).param("id", webhookEvent).update();
            if (inserted == 0) return;
        }
        store.db.sql("""
            INSERT INTO payments(hold_id,provider,provider_order_id,provider_payment_id,amount_minor)
            VALUES(:hold,'RAZORPAY',:order,:payment,:amount) ON CONFLICT (provider_payment_id) DO NOTHING
            """).param("hold", order.holdId()).param("order", order.orderId()).param("payment", payment.id())
            .param("amount", payment.amount()).update();
        Attempt attempt = store.db.sql("""
            SELECT id,hold_id,status,provider,amount_minor FROM payments WHERE provider_payment_id=:ref FOR UPDATE
            """).param("ref", payment.id()).query(Attempt.class).single();
        if (!attempt.holdId.equals(order.holdId())) {
            throw ApiFault.conflict("PAYMENT_MISMATCH", "This gateway payment belongs to another order.");
        }
        if (!attempt.status.equals("PENDING")) return;
        if (payment.amountRefunded() >= payment.amount()) {
            transition(attempt, "REFUNDED");
            return;
        }
        if (payment.amountRefunded() != 0 || !payment.captured() || !"captured".equals(payment.status())) {
            transition(attempt, "REFUND_FAILED");
            store.audit(null, "PAYMENT_REVIEW_REQUIRED", attempt.id, "Razorpay returned an inconsistent refund state.");
            return;
        }
        confirm(attempt, holds.lockHold(attempt.holdId));
    }

    @Transactional
    public void completeFree(UUID user, UUID id) {
        store.boundLocks();
        var hold = holds.ownHold(user, id, true);
        if (hold.total() != 0) throw ApiFault.bad("This booking requires a payment.");
        completeLocal(hold, "FREE");
    }

    @Transactional
    public void completeDemo(UUID user, UUID id) {
        store.boundLocks();
        var hold = holds.ownHold(user, id, true);
        if (!orders.acceptsDemoPayments(hold.eventId())) throw ApiFault.missing("SeatLock Pay is unavailable for this reservation.");
        if (hold.total() <= 0) throw ApiFault.bad("This reservation is complimentary.");
        if (orders.byHold(id).isPresent()) {
            throw ApiFault.conflict("PAYMENT_ORDER_EXISTS", "Continue with the existing Razorpay order for this reservation.");
        }
        completeLocal(hold, "SEATLOCK_DEMO");
    }

    private void completeLocal(Reservations.Hold hold, String provider) {
        if ("CONVERTED".equals(hold.status())) return;
        if (!hold.activeAt(store.now())) throw ApiFault.conflict("HOLD_INACTIVE", "This hold has ended. Choose seats again.");
        var attempt = store.db.sql("""
            INSERT INTO payments(hold_id,provider,amount_minor) VALUES(:id,:provider,:amount)
            RETURNING id,hold_id,status,provider,amount_minor
            """).param("id", hold.id()).param("provider", provider).param("amount", hold.total()).query(Attempt.class).single();
        confirm(attempt, hold);
    }

    private void confirm(Attempt payment, Reservations.Hold hold) {
        List<Long> ids = holds.heldIds(hold.id());
        List<Reservations.LockedSeat> seats = holds.lockSeats(hold.eventId(), ids);
        var now = store.now();
        boolean ownsAll = !ids.isEmpty() && seats.size() == ids.size() && seats.stream()
            .allMatch(seat -> "HELD".equals(seat.state()) && hold.id().equals(seat.holdId()) && seat.expiresAt().isAfter(now));
        if (!hold.activeAt(now) || !ownsAll || payment.amountMinor != hold.total()) {
            if (hold.status().equals("ACTIVE") && !hold.activeAt(now)) holds.release(hold, "EXPIRED");
            if (!payment.provider.equals("RAZORPAY")) {
                throw ApiFault.conflict("HOLD_INACTIVE", "This hold has ended. Choose seats again.");
            }
            queueRefund(payment.id);
            transition(payment, "REFUND_PENDING");
            store.audit(null, "LATE_PAYMENT_REFUND_QUEUED", payment.id, "The verified payment could not be fulfilled.");
            store.outbox(hold.eventId(), "PAYMENT_REFUND_QUEUED", payment.id, hold.userId());
            return;
        }
        UUID booking = store.db.sql("""
            INSERT INTO bookings(user_id,event_id,hold_id,payment_id,amount_minor)
            VALUES(:user,:event,:hold,:payment,:amount) RETURNING id
            """).param("user", hold.userId()).param("event", hold.eventId()).param("hold", hold.id())
            .param("payment", payment.id).param("amount", payment.amountMinor).query(UUID.class).single();
        store.db.sql("""
            INSERT INTO booking_items(booking_id,hold_id,event_id,seat_id,price_minor)
            SELECT :booking,hold_id,event_id,seat_id,price_minor FROM hold_items WHERE hold_id=:hold
            """).param("booking", booking).param("hold", hold.id()).update();
        store.db.sql("""
            INSERT INTO tickets(booking_item_id,event_id,seat_id)
            SELECT id,event_id,seat_id FROM booking_items WHERE booking_id=:booking
            """).param("booking", booking).update();
        store.db.sql("""
            UPDATE event_seats SET state='SOLD',hold_id=NULL,expires_at=NULL,revision=revision+1 WHERE hold_id=:hold
            """).param("hold", hold.id()).update();
        store.db.sql("UPDATE holds SET status='CONVERTED' WHERE id=:hold").param("hold", hold.id()).update();
        store.db.sql("UPDATE waitlist_entries SET state='ACCEPTED' WHERE offer_hold_id=:hold AND state='OFFERED'")
            .param("hold", hold.id()).update();
        transition(payment, "APPROVED");
        store.audit(hold.userId(), "BOOKING_CONFIRMED", booking, null);
        store.inventoryChanged(hold.eventId(), "BOOKING_CONFIRMED", booking, hold.userId());
    }

    void queueRefund(UUID payment) {
        store.db.sql("INSERT INTO payment_refunds(payment_id) VALUES(:id) ON CONFLICT (payment_id) DO NOTHING")
            .param("id", payment).update();
    }

    private void transition(Attempt attempt, String status) {
        store.db.sql("UPDATE payments SET status=:status WHERE id=:id")
            .param("status", status).param("id", attempt.id).update();
        metrics.counter("seatlock.payment.transitions", "status", status).increment();
    }

    List<PaymentView> attempts(UUID hold) {
        return store.db.sql("""
            SELECT p.id,p.hold_id,p.status,p.provider,o.mode,p.scenario,p.amount_minor,b.id AS booking_id,p.created_at
            FROM payments p LEFT JOIN bookings b ON b.payment_id=p.id LEFT JOIN razorpay_orders o ON o.hold_id=p.hold_id
            WHERE p.hold_id=:hold ORDER BY p.created_at DESC LIMIT 30
            """).param("hold", hold).query(PaymentView.class).list();
    }
}
