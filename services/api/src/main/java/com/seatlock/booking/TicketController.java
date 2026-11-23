package com.seatlock.booking;

import static com.seatlock.account.AuthController.user;

import com.seatlock.shared.RateLimits;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.security.Principal;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1")
class TicketController {
    record CheckIn(@NotBlank @Size(max = 100) String payload) {}
    record Refund(@NotBlank @Size(max = 400) String reason) {}
    private final Tickets tickets;
    private final RateLimits limits;

    TicketController(Tickets tickets, RateLimits limits) {
        this.tickets = tickets;
        this.limits = limits;
    }

    @GetMapping("/me/tickets")
    List<Tickets.Ticket> tickets(Principal principal) {
        return tickets.list(user(principal));
    }

    @GetMapping("/me/tickets/{id}")
    Tickets.Ticket ticket(Principal principal, @PathVariable UUID id) {
        return tickets.get(user(principal), id);
    }

    @GetMapping(value = "/me/tickets/{id}/qr", produces = MediaType.IMAGE_PNG_VALUE)
    ResponseEntity<byte[]> qr(Principal principal, @PathVariable UUID id) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).contentType(MediaType.IMAGE_PNG)
            .body(tickets.qr(user(principal), id));
    }

    @GetMapping(value = "/me/tickets/{id}/download", produces = MediaType.TEXT_HTML_VALUE)
    ResponseEntity<String> download(Principal principal, @PathVariable UUID id) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
            .header("Content-Disposition", "attachment; filename=seatlock-" + id + ".html")
            .header("Content-Security-Policy", "default-src 'none'; img-src data:; style-src 'unsafe-inline'")
            .body(tickets.downloadable(user(principal), id));
    }

    @GetMapping("/me/bookings")
    List<Tickets.BookingRow> bookings(Principal principal) {
        return tickets.bookings(user(principal));
    }

    @GetMapping("/me/bookings/{id}")
    Tickets.Booking booking(Principal principal, @PathVariable UUID id) {
        return tickets.booking(user(principal), id);
    }

    @PostMapping("/organizer/check-in")
    Tickets.Scan checkIn(Principal principal, @RequestBody @Valid CheckIn body) {
        UUID organizer = user(principal);
        limits.check("scan", organizer, 180, Duration.ofMinutes(1));
        return tickets.checkIn(organizer, body.payload);
    }

    @GetMapping("/admin/bookings")
    List<Tickets.BookingRow> adminBookings() {
        return tickets.adminBookings();
    }

    @GetMapping("/admin/bookings/{id}")
    Tickets.Inspection adminBooking(@PathVariable UUID id) {
        return tickets.inspect(id);
    }

    @PostMapping("/admin/bookings/{id}/refund")
    Tickets.BookingRow refund(Principal principal, @PathVariable UUID id, @RequestBody @Valid Refund request) {
        return tickets.refund(user(principal), id, request.reason);
    }
}
