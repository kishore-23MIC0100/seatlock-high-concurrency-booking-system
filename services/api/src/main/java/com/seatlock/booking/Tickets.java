package com.seatlock.booking;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.client.j2se.MatrixToImageWriter;
import com.google.zxing.qrcode.QRCodeWriter;
import com.seatlock.shared.ApiFault;
import com.seatlock.shared.Store;
import java.io.ByteArrayOutputStream;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.util.HtmlUtils;

@Service
public class Tickets {
    public record Ticket(UUID id, UUID bookingId, String eventId, String eventName, String venue, String city,
                         String zoneId, OffsetDateTime startsAt, String section, String row, int number,
                         String state, boolean demo) {}
    public record Booking(UUID id, String eventId, String eventName, String status, long amountMinor,
                          String currency, String provider, String mode, UUID paymentId, OffsetDateTime createdAt, List<Ticket> tickets) {}
    public record BookingRow(UUID id, UUID userId, String eventId, String eventName, String status,
                             long amountMinor, String currency, String provider, String mode, UUID paymentId, OffsetDateTime createdAt) {}
    public record Inspection(BookingRow booking, List<PaymentFulfillment.PaymentView> payments,
                             List<AuditEntry> timeline) {}
    public record AuditEntry(long id, UUID actorId, String action, String target, String detail, OffsetDateTime createdAt) {}
    record BookingPayment(UUID id, UUID holdId, String provider) {}
    record Scan(UUID id, String eventName, String row, int number, String state) {}

    private static final String TICKET_FIELDS = """
        SELECT t.id,b.id AS booking_id,e.id AS event_id,e.name AS event_name,v.name AS venue,v.city,v.zone_id,
          e.starts_at,c.name AS section,s.row_label AS row,s.seat_number AS number,
          CASE WHEN b.status='REFUND_PENDING' THEN 'REFUND_PENDING' ELSE t.state END AS state,e.demo
        FROM tickets t JOIN booking_items bi ON bi.id=t.booking_item_id JOIN bookings b ON b.id=bi.booking_id
        JOIN events e ON e.id=t.event_id JOIN venues v ON v.id=e.venue_id
        JOIN seats s ON s.id=t.seat_id JOIN sections c ON c.id=s.section_id
        """;
    private static final String BOOKINGS = """
        SELECT b.id,b.user_id,b.event_id,e.name AS event_name,b.status,b.amount_minor,b.currency,p.provider,o.mode,b.payment_id,b.created_at
        FROM bookings b JOIN events e ON e.id=b.event_id JOIN payments p ON p.id=b.payment_id
        LEFT JOIN razorpay_orders o ON o.order_id=p.provider_order_id
        """;
    private final Store store;
    private final Reservations holds;
    private final PaymentFulfillment payments;
    private final RazorpayClient gateway;

    Tickets(Store store, Reservations holds, PaymentFulfillment payments, RazorpayClient gateway) {
        this.store = store;
        this.holds = holds;
        this.payments = payments;
        this.gateway = gateway;
    }

    public List<Ticket> list(UUID customer) {
        return store.db.sql(TICKET_FIELDS + " WHERE b.user_id=:user ORDER BY b.created_at DESC,c.id,s.row_label,s.seat_number LIMIT 200")
            .param("user", customer).query(Ticket.class).list();
    }

    public Ticket get(UUID customer, UUID id) {
        return store.db.sql(TICKET_FIELDS + " WHERE b.user_id=:user AND t.id=:id")
            .param("user", customer).param("id", id).query(Ticket.class).optional()
            .orElseThrow(() -> ApiFault.missing("Ticket not found."));
    }

    public List<BookingRow> bookings(UUID customer) {
        return store.db.sql(BOOKINGS + " WHERE b.user_id=:user ORDER BY b.created_at DESC LIMIT 200")
            .param("user", customer).query(BookingRow.class).list();
    }

    @Transactional(readOnly = true, isolation = org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
    public Booking booking(UUID customer, UUID id) {
        BookingRow row = store.db.sql(BOOKINGS + " WHERE b.user_id=:user AND b.id=:id")
            .param("user", customer).param("id", id).query(BookingRow.class).optional()
            .orElseThrow(() -> ApiFault.missing("Booking not found."));
        var tickets = store.db.sql(TICKET_FIELDS + " WHERE b.id=:id ORDER BY c.id,s.row_label,s.seat_number")
            .param("id", id).query(Ticket.class).list();
        return new Booking(row.id, row.eventId, row.eventName, row.status, row.amountMinor, row.currency, row.provider,
            row.mode, row.paymentId, row.createdAt, tickets);
    }

    public byte[] qr(UUID customer, UUID id) {
        UUID token = store.db.sql("""
            SELECT t.qr_token FROM tickets t JOIN booking_items i ON i.id=t.booking_item_id JOIN bookings b ON b.id=i.booking_id
            WHERE t.id=:id AND b.user_id=:user AND t.state<>'VOID' AND b.status='CONFIRMED'
            """).param("id", id).param("user", customer).query(UUID.class).optional()
            .orElseThrow(() -> ApiFault.missing("Active ticket not found."));
        try (var buffer = new ByteArrayOutputStream()) {
            var matrix = new QRCodeWriter().encode("SL1." + token, BarcodeFormat.QR_CODE, 220, 220,
                java.util.Map.of(EncodeHintType.MARGIN, 3));
            MatrixToImageWriter.writeToStream(matrix, "PNG", buffer);
            return buffer.toByteArray();
        } catch (Exception error) {
            throw new IllegalStateException("QR generation failed", error);
        }
    }

    public String downloadable(UUID customer, UUID id) {
        Ticket ticket = get(customer, id);
        String date = DateTimeFormatter.ofPattern("EEE, d MMM yyyy · h:mm a z", Locale.ENGLISH)
            .format(ticket.startsAt.atZoneSameInstant(ZoneId.of(ticket.zoneId)));
        String qr = Base64.getEncoder().encodeToString(qr(customer, id));
        String label = ticket.demo ? "Demo ticket" : "Admission ticket";
        return """
            <!doctype html><html lang="en"><meta charset="utf-8"><title>SeatLock ticket</title>
            <meta name="viewport" content="width=device-width, initial-scale=1">
            <style>body{font:16px system-ui;color:#171329;margin:3rem auto;max-width:27rem;padding:0 1rem}
            main{border:1px solid #b8b1ca;border-radius:16px;padding:2rem}h1{margin:.4em 0}
            small{display:block;overflow-wrap:anywhere}img{display:block;margin:1rem auto}</style>
            <main><strong>SeatLock / %s</strong><h1>%s</h1><p>%s · %s<br>%s</p>
            <p><strong>%s / Seat %s%d</strong></p><img alt="Ticket QR code" width="220" height="220"
              src="data:image/png;base64,%s"><small>Ticket %s</small></main></html>
            """.formatted(label, escaped(ticket.eventName), escaped(ticket.venue), escaped(ticket.city), escaped(date),
                escaped(ticket.section), escaped(ticket.row), ticket.number, qr, id);
    }

    @Transactional
    public Scan checkIn(UUID organizer, String qrPayload) {
        UUID token;
        try {
            String text = qrPayload.strip();
            if (!text.startsWith("SL1.")) throw new IllegalArgumentException();
            token = UUID.fromString(text.substring(4));
        } catch (IllegalArgumentException invalid) {
            throw ApiFault.bad("Enter a valid SeatLock ticket code.");
        }
        store.boundLocks();
        UUID booking = store.db.sql("""
            SELECT i.booking_id FROM tickets t JOIN booking_items i ON i.id=t.booking_item_id JOIN events e ON e.id=t.event_id
            WHERE t.qr_token=:token AND e.organizer_id=:organizer
            """).param("token", token).param("organizer", organizer).query(UUID.class).optional()
            .orElseThrow(() -> ApiFault.missing("Ticket not found for your events."));
        String status = store.db.sql("SELECT status FROM bookings WHERE id=:id FOR UPDATE")
            .param("id", booking).query(String.class).single();
        if (status.equals("REFUND_PENDING")) throw ApiFault.conflict("REFUND_PENDING", "This ticket has a refund in progress.");
        Scan ticket = store.db.sql("""
            SELECT t.id,e.name AS event_name,s.row_label AS row,s.seat_number AS number,t.state
            FROM tickets t JOIN events e ON e.id=t.event_id JOIN seats s ON s.id=t.seat_id
            WHERE t.qr_token=:token AND e.organizer_id=:organizer FOR UPDATE OF t
            """).param("token", token).param("organizer", organizer).query(Scan.class).optional()
            .orElseThrow(() -> ApiFault.missing("Ticket not found for your events."));
        if (ticket.state.equals("CHECKED_IN")) throw ApiFault.conflict("ALREADY_CHECKED_IN", "This ticket already entered the venue.");
        if (!ticket.state.equals("VALID")) throw ApiFault.conflict("TICKET_VOID", "This ticket was cancelled.");
        store.db.sql("INSERT INTO ticket_checkins(ticket_id,organizer_id) VALUES(:ticket,:organizer)")
            .param("ticket", ticket.id).param("organizer", organizer).update();
        store.db.sql("UPDATE tickets SET state='CHECKED_IN' WHERE id=:id").param("id", ticket.id).update();
        store.audit(organizer, "TICKET_CHECKED_IN", ticket.id, null);
        return new Scan(ticket.id, ticket.eventName, ticket.row, ticket.number, "CHECKED_IN");
    }

    public List<BookingRow> adminBookings() {
        return store.db.sql(BOOKINGS + " ORDER BY b.created_at DESC LIMIT 200").query(BookingRow.class).list();
    }

    @Transactional(readOnly = true)
    public Inspection inspect(UUID id) {
        BookingRow booking = store.db.sql(BOOKINGS + " WHERE b.id=:id")
            .param("id", id).query(BookingRow.class).optional().orElseThrow(() -> ApiFault.missing("Booking not found."));
        var attempts = payments.attempts(bookingPayment(id).holdId);
        List<AuditEntry> timeline = store.db.sql("""
            SELECT id,actor_id,action,target,detail,created_at FROM audit_logs
            WHERE target=:booking OR target IN (
              SELECT p.id::text FROM payments p JOIN bookings b ON b.hold_id=p.hold_id WHERE b.id=:id)
            ORDER BY id DESC LIMIT 120
            """).param("booking", id.toString()).param("id", id).query(AuditEntry.class).list();
        return new Inspection(booking, attempts, timeline);
    }

    @Transactional
    public BookingRow refund(UUID admin, UUID id, String reason) {
        store.boundLocks();
        BookingRow booking = lockBooking(id);
        if (!booking.status.equals("CONFIRMED")) return booking;
        var start = store.db.sql("SELECT starts_at FROM events WHERE id=:id")
            .param("id", booking.eventId).query(OffsetDateTime.class).single();
        if (!start.isAfter(store.now())) throw ApiFault.conflict("REFUND_CLOSED", "Refunds close at event start.");
        List<String> states = store.db.sql("""
            SELECT t.state FROM tickets t JOIN booking_items i ON i.id=t.booking_item_id
            WHERE i.booking_id=:id ORDER BY t.seat_id FOR UPDATE OF t
            """).param("id", id).query(String.class).list();
        if (states.contains("CHECKED_IN")) throw ApiFault.conflict("ALREADY_CHECKED_IN", "A checked-in ticket cannot be refunded.");
        if (!start.isAfter(store.now())) throw ApiFault.conflict("REFUND_CLOSED", "Refunds close at event start.");
        var payment = bookingPayment(id);
        if (payment.provider.equals("RAZORPAY")) {
            gateway.requireConfigured();
            if (!gateway.mode().equals(booking.mode)) throw ApiFault.conflict("GATEWAY_CHANGED", "Use the gateway mode that accepted this payment.");
            boolean failed = store.db.sql("SELECT EXISTS(SELECT 1 FROM payment_refunds WHERE payment_id=:id AND status='FAILED')")
                .param("id", payment.id).query(Boolean.class).single();
            if (failed) throw ApiFault.conflict("REFUND_REVIEW", "Review the failed refund in Razorpay before taking further action.");
            payments.queueRefund(payment.id);
            store.db.sql("UPDATE payments SET status='REFUND_PENDING' WHERE id=:id").param("id", payment.id).update();
            store.db.sql("UPDATE bookings SET status='REFUND_PENDING' WHERE id=:id").param("id", id).update();
            store.audit(admin, "BOOKING_REFUND_QUEUED", id, reason.strip());
            return withStatus(booking, "REFUND_PENDING");
        }
        completeCancellation(booking, payment.id, admin, reason.strip());
        return withStatus(booking, "REFUNDED");
    }

    void completeGatewayRefund(UUID paymentId) {
        BookingRow booking = store.db.sql(BOOKINGS + " WHERE b.payment_id=:payment FOR UPDATE OF b")
            .param("payment", paymentId).query(BookingRow.class).optional().orElse(null);
        if (booking == null) {
            store.db.sql("UPDATE payments SET status='REFUNDED' WHERE id=:id").param("id", paymentId).update();
            store.db.sql("""
                INSERT INTO outbox_events(event_id,event_type,subject_id,user_id)
                SELECT h.event_id,'PAYMENT_REFUNDED',p.id,h.user_id FROM payments p JOIN holds h ON h.id=p.hold_id WHERE p.id=:id
                """).param("id", paymentId).update();
        } else if ("REFUND_PENDING".equals(booking.status)) {
            completeCancellation(booking, paymentId, null, "Razorpay confirmed the refund.");
        }
    }

    void failGatewayRefund(UUID paymentId) {
        boolean booking = store.db.sql("""
            UPDATE bookings SET status='CONFIRMED' WHERE payment_id=:id AND status='REFUND_PENDING'
            """).param("id", paymentId).update() == 1;
        store.db.sql("UPDATE payments SET status=:status WHERE id=:id")
            .param("status", booking ? "APPROVED" : "REFUND_FAILED").param("id", paymentId).update();
    }

    private BookingRow lockBooking(UUID id) {
        return store.db.sql(BOOKINGS + " WHERE b.id=:id FOR UPDATE OF b").param("id", id).query(BookingRow.class).optional()
            .orElseThrow(() -> ApiFault.missing("Booking not found."));
    }

    private BookingPayment bookingPayment(UUID id) {
        return store.db.sql("""
            SELECT p.id,p.hold_id,p.provider FROM payments p JOIN bookings b ON b.payment_id=p.id WHERE b.id=:id
            """).param("id", id).query(BookingPayment.class).single();
    }

    private void completeCancellation(BookingRow booking, UUID payment, UUID actor, String reason) {
        List<Long> ids = store.db.sql("SELECT seat_id FROM booking_items WHERE booking_id=:id ORDER BY seat_id")
            .param("id", booking.id).query(Long.class).list();
        holds.lockSeats(booking.eventId, ids);
        store.db.sql("""
            UPDATE tickets SET state='VOID' WHERE booking_item_id IN (SELECT id FROM booking_items WHERE booking_id=:id)
            """).param("id", booking.id).update();
        store.db.sql("""
            UPDATE event_seats SET state='AVAILABLE',revision=revision+1
            WHERE event_id=:event AND seat_id IN (:ids) AND state='SOLD'
            """).param("event", booking.eventId).param("ids", ids).update();
        store.db.sql("UPDATE payments SET status='REFUNDED' WHERE id=:id").param("id", payment).update();
        store.db.sql("UPDATE bookings SET status='REFUNDED' WHERE id=:id").param("id", booking.id).update();
        String action = booking.provider.equals("RAZORPAY") ? "BOOKING_REFUNDED" : "BOOKING_CANCELLED";
        store.audit(actor, action, booking.id, reason);
        store.inventoryChanged(booking.eventId, action, booking.id, booking.userId);
    }

    private static BookingRow withStatus(BookingRow booking, String status) {
        return new BookingRow(booking.id, booking.userId, booking.eventId, booking.eventName, status,
            booking.amountMinor, booking.currency, booking.provider, booking.mode, booking.paymentId, booking.createdAt);
    }

    private static String escaped(String value) {
        return HtmlUtils.htmlEscape(value);
    }
}
