package com.seatlock.booking;

import com.seatlock.shared.ApiFault;
import com.seatlock.shared.Store;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class PaymentOrders {
    record Order(UUID holdId, long amountMinor, String mode, String orderId, UUID provisionToken,
                 OffsetDateTime provisionUntil) {}
    record Claim(Order order, boolean recovering) {}

    private static final String COLUMNS = "hold_id,amount_minor,mode,order_id,provision_token,provision_until";
    private final Store store;
    private final Reservations holds;
    private final boolean demoPayments;

    PaymentOrders(Store store, Reservations holds, @Value("${seatlock.demo-payments}") boolean demoPayments,
                  @Value("${seatlock.seed}") boolean seed, @Value("${seatlock.razorpay.key-id}") String paymentKey) {
        this.store = store;
        this.holds = holds;
        this.demoPayments = demoPayments && seed && !paymentKey.strip().startsWith("rzp_live_");
    }

    @Transactional
    public Claim claim(UUID user, UUID id, String mode) {
        store.boundLocks();
        var hold = holds.ownHold(user, id, true);
        if (!hold.activeAt(store.now())) throw ApiFault.conflict("HOLD_INACTIVE", "This hold has ended. Choose seats again.");
        if (hold.total() < 100) throw ApiFault.bad("Razorpay requires at least ₹1 for a paid order.");
        if ("LIVE".equals(mode) && demoEvent(hold.eventId())) {
            throw ApiFault.conflict("DEMO_EVENT", "Live payments are unavailable for demo events.");
        }
        if (acceptsDemoPayments(hold.eventId()) && byHold(id).isEmpty()) {
            throw ApiFault.conflict("DEMO_PAYMENT_REQUIRED", "Use SeatLock Pay for this demo reservation.");
        }
        store.db.sql("""
            INSERT INTO razorpay_orders(hold_id,amount_minor,mode) VALUES(:id,:amount,:mode)
            ON CONFLICT (hold_id) DO NOTHING
            """).param("id", id).param("amount", hold.total()).param("mode", mode).update();
        Order order = byHold(id).orElseThrow();
        if (!order.mode().equals(mode)) throw ApiFault.conflict("GATEWAY_CHANGED", "The gateway configuration changed. Start a new hold.");
        if (order.orderId != null) return new Claim(order, false);
        if (order.provisionUntil.isAfter(store.now())) {
            throw ApiFault.conflict("ORDER_CREATING", "Razorpay checkout is being prepared. Try again in a few seconds.");
        }
        var next = store.db.sql("""
            UPDATE razorpay_orders SET provision_token=gen_random_uuid(),provision_until=clock_timestamp()+interval '20 seconds'
            WHERE hold_id=:id RETURNING
            """ + " " + COLUMNS).param("id", id).query(Order.class).single();
        return new Claim(next, order.provisionToken != null);
    }

    Order attach(Order claim, RazorpayClient.Order remote) {
        if (!claim.holdId.toString().equals(remote.receipt()) || remote.amount() != claim.amountMinor || !"INR".equals(remote.currency())) {
            throw ApiFault.conflict("ORDER_MISMATCH", "The gateway order does not match this reservation.");
        }
        int updated = store.db.sql("""
            UPDATE razorpay_orders SET order_id=:order,provision_token=NULL,reconcile_after=clock_timestamp()+interval '12 seconds'
            WHERE hold_id=:hold AND provision_token=:token AND order_id IS NULL
            """).param("order", remote.id()).param("hold", claim.holdId).param("token", claim.provisionToken).update();
        return byHold(claim.holdId).filter(order -> updated == 1 || order.orderId != null)
            .orElseThrow(() -> ApiFault.conflict("ORDER_CREATING", "Razorpay checkout is being prepared. Try again shortly."));
    }

    Optional<Order> byHold(UUID hold) {
        return store.db.sql("SELECT " + COLUMNS + " FROM razorpay_orders WHERE hold_id=:id")
            .param("id", hold).query(Order.class).optional();
    }

    Optional<Order> byGatewayId(String id) {
        return store.db.sql("SELECT " + COLUMNS + " FROM razorpay_orders WHERE order_id=:id")
            .param("id", id).query(Order.class).optional();
    }

    boolean demoEvent(String eventId) {
        return store.db.sql("SELECT demo FROM events WHERE id=:id").param("id", eventId).query(Boolean.class).single();
    }

    boolean acceptsDemoPayments(String eventId) {
        return demoPayments && demoEvent(eventId);
    }

    @Transactional
    public List<Order> claimReconciliation(String mode) {
        return store.db.sql("""
            WITH due AS (
              SELECT o.hold_id, h.expires_at FROM razorpay_orders o JOIN holds h ON h.id=o.hold_id
              WHERE o.mode=:mode AND o.order_id IS NOT NULL AND o.reconcile_after<=clock_timestamp()
                AND h.expires_at>clock_timestamp()-interval '3 days'
                AND NOT EXISTS (SELECT 1 FROM payments p WHERE p.provider_order_id=o.order_id)
              ORDER BY o.reconcile_after LIMIT 6 FOR UPDATE OF o SKIP LOCKED
            )
            UPDATE razorpay_orders o SET reconcile_after=clock_timestamp() +
               CASE WHEN due.expires_at>clock_timestamp() THEN interval '25 seconds' ELSE interval '10 minutes' END
            FROM due WHERE o.hold_id=due.hold_id
            RETURNING o.hold_id,o.amount_minor,o.mode,o.order_id,o.provision_token,o.provision_until
            """).param("mode", mode).query(Order.class).list();
    }
}
