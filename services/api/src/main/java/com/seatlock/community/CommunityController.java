package com.seatlock.community;

import static com.seatlock.account.AuthController.user;

import com.seatlock.shared.RateLimits;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.security.Principal;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1")
class CommunityController {
    record WaitlistRequest(@NotBlank @Size(max = 100) String eventId, @Min(1) @Max(6) int quantity,
                           boolean needsAccessible) {}
    private final Community community;
    private final RateLimits limits;

    CommunityController(Community community, RateLimits limits) {
        this.community = community;
        this.limits = limits;
    }

    @GetMapping("/waitlist")
    List<Community.Entry> entries(Principal principal) {
        return community.entries(user(principal));
    }

    @PostMapping("/waitlist")
    @ResponseStatus(HttpStatus.CREATED)
    Community.Entry join(Principal principal, @RequestBody @Valid WaitlistRequest body) {
        UUID user = user(principal);
        limits.check("waitlist", user, 20, Duration.ofMinutes(1));
        return community.join(user, body.eventId, body.quantity, body.needsAccessible);
    }

    @DeleteMapping("/waitlist/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void leave(Principal principal, @PathVariable long id) {
        community.leave(user(principal), id);
    }

    @GetMapping("/events/{id}/admission")
    Community.Admission admission(Principal principal, @PathVariable String id) {
        return community.admission(user(principal), id);
    }

    @PostMapping("/events/{id}/admission")
    Community.Admission enter(Principal principal, @PathVariable String id) {
        UUID user = user(principal);
        limits.check("admission", user, 20, Duration.ofMinutes(1));
        return community.enter(user, id);
    }

    @GetMapping("/me/notifications")
    List<Community.Notice> notices(Principal principal) {
        return community.notices(user(principal));
    }

    @PostMapping("/me/notifications/read")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void markRead(Principal principal) {
        community.markRead(user(principal));
    }
}
