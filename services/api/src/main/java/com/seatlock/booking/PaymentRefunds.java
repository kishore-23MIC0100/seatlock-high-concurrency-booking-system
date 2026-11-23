package com.seatlock.booking;

import com.seatlock.shared.ApiFault;
import com.seatlock.shared.Store;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class PaymentRefunds {
    record Job(UUID id, UUID paymentId, String paymentReference, String providerRefundId, long amount) {}
    private final Store store;
    private final Tickets tickets;

    PaymentRefunds(Store store, Tickets tickets) {
        this.store = store;
        this.tickets = tickets;
    }

    @Transactional
    public List<Job> claim(String mode) {
        return store.db.sql("""
            WITH due AS (
              SELECT r.id,p.provider_payment_id,p.amount_minor FROM payment_refunds r JOIN payments p ON p.id=r.payment_id
              JOIN razorpay_orders o ON o.order_id=p.provider_order_id
              WHERE r.status='PENDING' AND r.next_attempt_at<=clock_timestamp() AND o.mode=:mode
              ORDER BY r.next_attempt_at LIMIT 4 FOR UPDATE OF r SKIP LOCKED
            )
            UPDATE payment_refunds r SET next_attempt_at=clock_timestamp()+interval '2 minutes'
            FROM due WHERE r.id=due.id
            RETURNING r.id,r.payment_id,due.provider_payment_id AS payment_reference,r.provider_refund_id,due.amount_minor AS amount
            """).param("mode", mode).query(Job.class).list();
    }

    @Transactional
    public void complete(Job job, RazorpayClient.Refund result) {
        store.boundLocks();
        if (!job.paymentReference.equals(result.paymentId()) || job.amount != result.amount()
                || (job.providerRefundId != null && !job.providerRefundId.equals(result.id()))) {
            throw ApiFault.conflict("REFUND_MISMATCH", "The gateway refund does not match the recorded request.");
        }
        String status = switch (result.status()) {
            case "processed" -> "PROCESSED";
            case "failed" -> "FAILED";
            case "pending" -> "PENDING";
            default -> throw ApiFault.conflict("REFUND_STATUS_UNKNOWN", "The gateway returned an unknown refund status.");
        };
        int changed = store.db.sql("""
            UPDATE payment_refunds SET provider_refund_id=:ref,status=:status,error_code=:error,
              completed_at=CASE WHEN :status<>'PENDING' THEN clock_timestamp() END,
              attempts=0,next_attempt_at=clock_timestamp()+interval '2 minutes'
            WHERE id=:id AND status='PENDING' AND (provider_refund_id IS NULL OR provider_refund_id=:ref)
            """).param("ref", result.id()).param("status", status)
            .param("error", status.equals("FAILED") ? "PROVIDER_REFUND_FAILED" : null).param("id", job.id).update();
        if (changed == 0) return;
        if ("PROCESSED".equals(status)) tickets.completeGatewayRefund(job.paymentId);
        if ("FAILED".equals(status)) tickets.failGatewayRefund(job.paymentId);
        if (!"PENDING".equals(status)) store.audit(null, "RAZORPAY_REFUND_" + status, job.id, null);
    }

    void retry(Job job, String code) {
        store.db.sql("""
            UPDATE payment_refunds SET attempts=attempts+1,error_code=:error,
               next_attempt_at=clock_timestamp()+make_interval(secs=>least(900,15*power(2,least(attempts,6))))
            WHERE id=:id AND status='PENDING'
            """).param("error", code).param("id", job.id).update();
    }
}
