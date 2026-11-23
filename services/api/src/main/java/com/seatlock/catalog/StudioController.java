package com.seatlock.catalog;

import static com.seatlock.account.AuthController.user;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.security.Principal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/organizer")
class StudioController {
    enum Category { MUSIC, COMEDY, THEATRE, SPORTS }
    record NewVenue(@NotBlank @Size(max = 100) String name, @NotBlank @Size(max = 70) String city,
                    @NotBlank @Size(max = 70) String zoneId, @NotBlank @Size(max = 60) String section,
                    @Min(1) @Max(26) int rows, @Min(1) @Max(60) int seatsPerRow,
                    @NotNull @Size(max = 60) List<@NotBlank @Size(max = 5) String> accessible) {}
    record VenueMetadata(@NotBlank @Size(max = 100) String name, @NotBlank @Size(max = 70) String city) {}
    record NewEvent(@NotNull UUID venueId, @NotBlank @Size(max = 100) String name, @NotBlank @Size(max = 1500) String description,
                    @NotNull Category category, @NotNull OffsetDateTime startsAt, @NotNull OffsetDateTime endsAt,
                    @NotNull OffsetDateTime saleStartsAt, @Min(0) @Max(100000000) long priceMinor,
                    @Min(0) @Max(26) int premiumRows, @Min(0) @Max(100000000) Long premiumPriceMinor,
                    @Min(1) @Max(5000) Integer roomCapacity) {}
    record DraftMetadata(@NotBlank @Size(max = 100) String name, @NotBlank @Size(max = 1500) String description,
                         @NotNull OffsetDateTime startsAt, @NotNull OffsetDateTime endsAt, @NotNull OffsetDateTime saleStartsAt,
                         @Min(1) @Max(5000) Integer roomCapacity) {}
    record Block(@NotNull Boolean blocked) {}

    private final Studio studio;
    private final Catalog catalog;
    StudioController(Studio studio, Catalog catalog) {
        this.studio = studio;
        this.catalog = catalog;
    }

    @GetMapping("/events")
    List<Catalog.Event> events(Principal principal) {
        return catalog.organizerEvents(user(principal));
    }

    @PostMapping("/events")
    @ResponseStatus(HttpStatus.CREATED)
    Catalog.Event event(Principal principal, @RequestBody @Valid NewEvent body) {
        return studio.createEvent(user(principal), body);
    }

    @GetMapping("/events/{id}")
    Catalog.Inventory layout(Principal principal, @PathVariable String id) {
        return catalog.organizerInventory(user(principal), id);
    }

    @PatchMapping("/events/{id}")
    Catalog.Event updateDraft(Principal principal, @PathVariable String id, @RequestBody @Valid DraftMetadata body) {
        return studio.updateDraft(user(principal), id, body);
    }

    @PostMapping("/events/{id}/publish")
    Catalog.Event publish(Principal principal, @PathVariable String id) {
        return studio.publish(user(principal), id);
    }

    @PatchMapping("/events/{id}/seats/{seat}")
    Catalog.Inventory block(Principal principal, @PathVariable String id, @PathVariable long seat, @RequestBody @Valid Block body) {
        return studio.block(user(principal), id, seat, body.blocked());
    }

    @GetMapping("/venues")
    List<Studio.Venue> venues(Principal principal) {
        return studio.venues(user(principal));
    }

    @PostMapping("/venues")
    @ResponseStatus(HttpStatus.CREATED)
    Studio.Venue venue(Principal principal, @RequestBody @Valid NewVenue body) {
        return studio.createVenue(user(principal), body);
    }

    @GetMapping("/venues/{id}")
    Studio.Venue venue(Principal principal, @PathVariable UUID id) {
        return studio.venue(user(principal), id);
    }

    @PatchMapping("/venues/{id}")
    Studio.Venue editVenue(Principal principal, @PathVariable UUID id, @RequestBody @Valid VenueMetadata body) {
        return studio.renameVenue(user(principal), id, body);
    }

    @GetMapping("/venues/{id}/seats")
    List<Studio.VenueSeat> seats(Principal principal, @PathVariable UUID id) {
        return studio.layout(user(principal), id);
    }

    @GetMapping("/attendees")
    List<Studio.Attendee> attendees(Principal principal) {
        return studio.attendees(user(principal));
    }

    @GetMapping("/reports")
    List<Studio.Report> reports(Principal principal) {
        return studio.reports(user(principal));
    }

    @GetMapping(value = "/reports/download", produces = "text/csv")
    ResponseEntity<String> csv(Principal principal) {
        String rows = studio.reports(user(principal)).stream().map(result ->
            cell(result.eventName()) + "," + result.capacity() + "," + result.bookings() + "," + result.tickets()
              + "," + result.amountMinor() + "," + result.checkedIn()).collect(Collectors.joining("\r\n"));
        return ResponseEntity.ok().header("Content-Disposition", "attachment; filename=seatlock-sales.csv")
            .body("Event,Capacity,Bookings,Tickets,Live Revenue INR minor units,Checked In\r\n" + rows);
    }

    private static String cell(String value) {
        String safe = value.strip();
        if (!safe.isEmpty() && "=+-@".indexOf(safe.charAt(0)) >= 0) safe = "'" + safe;
        return "\"" + safe.replace("\"", "\"\"") + "\"";
    }
}
