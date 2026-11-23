package com.seatlock.booking;

import static com.seatlock.account.AuthController.user;

import com.seatlock.shared.RateLimits;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.security.Principal;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1")
class ReservationController {
    record HoldRequest(@NotBlank @Size(max = 100) String eventId,
                       @Size(min = 1, max = Reservations.SEAT_LIMIT) @NotNull List<@Positive @NotNull Long> seatIds) {}

    private final Reservations holds;
    private final RateLimits limits;

    ReservationController(Reservations holds, RateLimits limits) {
        this.holds = holds;
        this.limits = limits;
    }

    @PostMapping("/holds")
    @ResponseStatus(HttpStatus.CREATED)
    Reservations.HoldView create(Principal principal, @RequestHeader(value = "Idempotency-Key", required = false) String key,
                                 @RequestBody @Valid HoldRequest request) {
        UUID user = user(principal);
        limits.check("hold", user, 30, Duration.ofMinutes(1));
        return holds.create(user, request.eventId, request.seatIds, key);
    }

    @GetMapping("/holds/{id}")
    Reservations.HoldView read(Principal principal, @PathVariable UUID id) {
        return holds.view(user(principal), id);
    }

    @GetMapping("/me/holds")
    List<Reservations.HoldView> active(Principal principal) {
        return holds.active(user(principal));
    }

    @DeleteMapping("/holds/{id}")
    Reservations.HoldView cancel(Principal principal, @PathVariable UUID id) {
        return holds.cancel(user(principal), id);
    }
}
