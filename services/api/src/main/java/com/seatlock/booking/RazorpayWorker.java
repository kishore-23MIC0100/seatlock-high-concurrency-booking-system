package com.seatlock.booking;

import com.seatlock.shared.ApiFault;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "seatlock.workers.enabled", havingValue = "true")
class RazorpayWorker {
    private static final Logger log = LoggerFactory.getLogger(RazorpayWorker.class);
    private final CheckoutPayments checkout;
    private final PaymentOrders orders;
    private final PaymentRefunds refunds;
    private final RazorpayClient gateway;
    private final MeterRegistry metrics;

    RazorpayWorker(CheckoutPayments checkout, PaymentOrders orders, PaymentRefunds refunds, RazorpayClient gateway,
                   MeterRegistry metrics) {
        this.checkout = checkout;
        this.orders = orders;
        this.refunds = refunds;
        this.gateway = gateway;
        this.metrics = metrics;
    }

    @Scheduled(fixedRate = 6000)
    void reconcile() {
        if (!gateway.configured()) return;
        for (var order : orders.claimReconciliation(gateway.mode())) {
            try {
                checkout.reconcile(order);
            } catch (RuntimeException error) {
                recordFailure("razorpay-reconcile", order.holdId(), error);
            }
        }
    }

    @Scheduled(fixedRate = 5000)
    void processRefunds() {
        if (!gateway.configured()) return;
        for (var job : refunds.claim(gateway.mode())) {
            try {
                var result = job.providerRefundId() == null
                    ? gateway.startRefund(job.id(), job.paymentReference(), job.amount())
                    : gateway.refund(job.providerRefundId());
                refunds.complete(job, result);
            } catch (RuntimeException error) {
                recordFailure("razorpay-refund", job.id(), error);
                refunds.retry(job, error instanceof ApiFault fault ? fault.code : "REFUND_RETRY");
            }
        }
    }

    private void recordFailure(String job, Object id, RuntimeException error) {
        metrics.counter("seatlock.worker.failures", "worker", job).increment();
        log.warn("{} for {} will retry ({})", job, id, error.getClass().getSimpleName());
    }
}
