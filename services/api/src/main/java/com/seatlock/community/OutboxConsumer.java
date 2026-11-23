package com.seatlock.community;

import com.seatlock.shared.Store;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class OutboxConsumer {
    record Message(long id, String eventType, UUID userId, OffsetDateTime createdAt) {}
    private final Store store;

    OutboxConsumer(Store store) {
        this.store = store;
    }

    @Transactional
    public void process(long id) {
        Message message = store.db.sql("SELECT id,event_type,user_id,created_at FROM outbox_events WHERE id=:id")
            .param("id", id).query(Message.class).single();
        int inserted = store.db.sql("""
            INSERT INTO processed_events(consumer,event_id) VALUES('notification-inbox',:id)
            ON CONFLICT DO NOTHING
            """).param("id", id).update();
        if (inserted == 0 || message.userId == null) return;
        String text = switch (message.eventType) {
            case "BOOKING_CONFIRMED" -> "Booking confirmed. Your tickets are ready.";
            case "BOOKING_REFUNDED", "PAYMENT_REFUNDED" -> "Razorpay processed your refund.";
            case "BOOKING_CANCELLED" -> "Your booking was cancelled.";
            case "PAYMENT_REFUND_QUEUED" -> "Payment arrived after the reservation closed. A refund is being requested.";
            case "WAITLIST_OFFERED" -> "Seats are available for your waitlist entry. Claim them before the offer expires.";
            case "HOLD_EXPIRED" -> "Your seat hold expired.";
            default -> null;
        };
        if (text != null) {
            store.db.sql("INSERT INTO notifications(user_id,outbox_id,message) VALUES(:user,:id,:message)")
                .param("user", message.userId).param("id", id).param("message", text).update();
        }
    }
}
