package com.seatlock.booking;

import com.seatlock.shared.ApiFault;
import java.time.Duration;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

@Service
class CheckoutPayments {
    record State(String status, boolean enabled, String mode, String orderId, UUID bookingId) {}
    record CheckoutOrder(String key, String orderId, long amountMinor, String currency, int timeoutSeconds) {}

    private final RazorpayClient gateway;
    private final PaymentOrders orders;
    private final PaymentFulfillment fulfillment;
    private final Reservations holds;
    private final ObjectMapper json;

    CheckoutPayments(RazorpayClient gateway, PaymentOrders orders, PaymentFulfillment fulfillment, Reservations holds,
                     ObjectMapper json) {
        this.gateway = gateway;
        this.orders = orders;
        this.fulfillment = fulfillment;
        this.holds = holds;
        this.json = json;
    }

    State status(UUID user, UUID id) {
        var hold = holds.view(user, id);
        var order = orders.byHold(id).orElse(null);
        var captured = fulfillment.attempts(id).stream().filter(p -> !"LEGACY".equals(p.provider())).findFirst().orElse(null);
        boolean free = hold.totalMinor() == 0;
        boolean demo = !free && order == null && orders.acceptsDemoPayments(hold.eventId());
        boolean demoReceipt = captured != null && "SEATLOCK_DEMO".equals(captured.provider());
        boolean enabled = free || demo || (gateway.configured()
            && (order == null || order.mode().equals(gateway.mode()))
            && (!"LIVE".equals(gateway.mode()) || !orders.demoEvent(hold.eventId())));
        String mode = free ? "FREE" : demo || demoReceipt ? "DEMO" : order != null ? order.mode() : gateway.mode();
        String state = captured != null ? captured.status() : order != null ? "AWAITING_PAYMENT" : "READY";
        return new State(state, enabled, mode, order == null ? null : order.orderId(), hold.bookingId());
    }

    CheckoutOrder begin(UUID user, UUID id) {
        gateway.requireConfigured();
        var claim = orders.claim(user, id, gateway.mode());
        var order = claim.order();
        if (order.orderId() == null) {
            var recovered = claim.recovering() ? gateway.orderByReceipt(id) : java.util.Optional.<RazorpayClient.Order>empty();
            long amount = order.amountMinor();
            order = orders.attach(order, recovered.orElseGet(() -> gateway.createOrder(id, amount)));
        }
        var hold = holds.view(user, id);
        long remaining = Duration.between(hold.serverTime(), hold.expiresAt()).toSeconds();
        if (!"ACTIVE".equals(hold.status()) || remaining < 1) {
            throw ApiFault.conflict("HOLD_INACTIVE", "This hold has ended. Choose seats again.");
        }
        return new CheckoutOrder(gateway.publicKey(), order.orderId(), order.amountMinor(), "INR", (int) remaining);
    }

    State verify(UUID user, UUID holdId, String reportedOrder, String paymentId, String signature) {
        holds.view(user, holdId);
        var order = orders.byHold(holdId).filter(o -> o.orderId() != null)
            .orElseThrow(() -> ApiFault.missing("Payment order not found."));
        requireMode(order);
        if (!order.orderId().equals(reportedOrder) || !gateway.checkoutSignature(order.orderId(), paymentId, signature)) {
            throw ApiFault.forbidden("The payment confirmation signature is invalid.");
        }
        var verified = gateway.payment(paymentId);
        if (!paymentId.equals(verified.id()) || !order.orderId().equals(verified.orderId())) {
            throw ApiFault.conflict("PAYMENT_MISMATCH", "The payment belongs to another gateway order.");
        }
        if (verified.settled()) fulfillment.settleCapture(order, verified, null);
        return status(user, holdId);
    }

    State completeFree(UUID user, UUID holdId) {
        fulfillment.completeFree(user, holdId);
        return status(user, holdId);
    }

    State completeDemo(UUID user, UUID holdId) {
        fulfillment.completeDemo(user, holdId);
        return status(user, holdId);
    }

    State sync(UUID user, UUID holdId) {
        holds.view(user, holdId);
        orders.byHold(holdId).filter(order -> order.orderId() != null).ifPresent(this::reconcile);
        return status(user, holdId);
    }

    void reconcile(PaymentOrders.Order order) {
        requireMode(order);
        for (var payment : gateway.paymentsFor(order.orderId())) {
            if (payment.settled()) fulfillment.settleCapture(order, payment, null);
        }
    }

    void webhook(byte[] body, String signature, String eventId) {
        if (body.length > 131_072) throw ApiFault.bad("Webhook body is too large.");
        if (!gateway.webhookSignature(body, signature)) throw ApiFault.forbidden("Invalid Razorpay webhook signature.");
        if (eventId == null || !eventId.matches("[A-Za-z0-9._:-]{1,160}")) throw ApiFault.bad("Invalid Razorpay webhook event ID.");
        try {
            var payload = json.readTree(body);
            if (payload == null || !"payment.captured".equals(payload.path("event").asText())) return;
            var entity = payload.path("payload").path("payment").path("entity");
            var order = orders.byGatewayId(entity.path("order_id").asText()).orElse(null);
            if (order == null) return;
            requireMode(order);
            var verified = gateway.payment(entity.path("id").asText());
            if (!verified.settled()) {
                throw new ApiFault(HttpStatus.SERVICE_UNAVAILABLE, "CAPTURE_PENDING", "Payment capture has not been verified yet.");
            }
            if (!verified.id().equals(entity.path("id").asText())) throw ApiFault.bad("Unexpected Razorpay payment.");
            fulfillment.settleCapture(order, verified, eventId);
        } catch (JacksonException malformed) {
            throw ApiFault.bad("Invalid Razorpay webhook body.");
        }
    }

    private void requireMode(PaymentOrders.Order order) {
        gateway.requireConfigured();
        if (!order.mode().equals(gateway.mode())) {
            throw ApiFault.conflict("GATEWAY_CHANGED", "The gateway configuration has changed for this order.");
        }
    }
}
