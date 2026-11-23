package com.seatlock.booking;

import static com.seatlock.account.AuthController.user;

import com.seatlock.shared.RateLimits;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import java.security.Principal;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1")
class PaymentController {
    record Proof(@NotNull @Pattern(regexp = "order_[A-Za-z0-9]{4,60}") String orderId,
                 @NotNull @Pattern(regexp = "pay_[A-Za-z0-9]{4,60}") String paymentId,
                 @NotNull @Pattern(regexp = "[0-9a-fA-F]{64}") String signature) {}

    private final CheckoutPayments checkout;
    private final Reservations holds;
    private final PaymentFulfillment fulfillment;
    private final RateLimits limits;

    PaymentController(CheckoutPayments checkout, Reservations holds, PaymentFulfillment fulfillment, RateLimits limits) {
        this.checkout = checkout;
        this.holds = holds;
        this.fulfillment = fulfillment;
        this.limits = limits;
    }

    @GetMapping("/holds/{id}/payment")
    CheckoutPayments.State status(Principal principal, @PathVariable UUID id) {
        return checkout.status(user(principal), id);
    }

    @PostMapping("/holds/{id}/payment/order")
    CheckoutPayments.CheckoutOrder order(Principal principal, @PathVariable UUID id) {
        UUID owner = user(principal);
        limits.check("payment-order", owner, 15, Duration.ofMinutes(1));
        return checkout.begin(owner, id);
    }

    @PostMapping("/holds/{id}/payment/verify")
    CheckoutPayments.State verify(Principal principal, @PathVariable UUID id, @RequestBody @Valid Proof proof) {
        UUID owner = user(principal);
        limits.check("payment-verify", owner, 20, Duration.ofMinutes(1));
        return checkout.verify(owner, id, proof.orderId, proof.paymentId, proof.signature);
    }

    @PostMapping("/holds/{id}/payment/sync")
    CheckoutPayments.State sync(Principal principal, @PathVariable UUID id) {
        UUID owner = user(principal);
        limits.check("payment-sync", owner, 10, Duration.ofMinutes(1));
        return checkout.sync(owner, id);
    }

    @PostMapping("/holds/{id}/payment/free")
    CheckoutPayments.State free(Principal principal, @PathVariable UUID id) {
        UUID owner = user(principal);
        limits.check("payment-free", owner, 20, Duration.ofMinutes(1));
        return checkout.completeFree(owner, id);
    }

    @PostMapping("/holds/{id}/payment/demo")
    CheckoutPayments.State demoPayment(Principal principal, @PathVariable UUID id) {
        UUID owner = user(principal);
        limits.check("payment-demo", owner, 20, Duration.ofMinutes(1));
        return checkout.completeDemo(owner, id);
    }

    @GetMapping("/holds/{id}/payments")
    List<PaymentFulfillment.PaymentView> payments(Principal principal, @PathVariable UUID id) {
        holds.view(user(principal), id);
        return fulfillment.attempts(id);
    }

    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PostMapping(value = "/payments/razorpay/webhook", consumes = "application/json")
    void webhook(@RequestBody byte[] body,
                 @RequestHeader(value = "X-Razorpay-Signature", required = false) String signature,
                 @RequestHeader(value = "X-Razorpay-Event-Id", required = false) String eventId) {
        checkout.webhook(body, signature, eventId);
    }
}
