package com.seatlock.catalog;

import jakarta.servlet.http.HttpServletResponse;
import java.util.List;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
@RequestMapping("/api/v1/events")
class CatalogController {
    private final Catalog catalog;
    private final AvailabilityStream availability;

    CatalogController(Catalog catalog, AvailabilityStream availability) {
        this.catalog = catalog;
        this.availability = availability;
    }

    @GetMapping
    List<Catalog.Event> events(@RequestParam(defaultValue = "") String q,
                               @RequestParam(defaultValue = "") String category,
                               @RequestParam(defaultValue = "") String sort) {
        return catalog.events(q, category, sort);
    }

    @GetMapping("/{id}")
    Catalog.Event event(@PathVariable String id) {
        return catalog.event(id);
    }

    @GetMapping("/{id}/seats")
    Catalog.Inventory seats(@PathVariable String id, HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-store");
        return catalog.inventory(id);
    }

    @GetMapping(value = "/{id}/availability/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    SseEmitter stream(@PathVariable String id, HttpServletResponse response) {
        catalog.event(id);
        response.setHeader("X-Accel-Buffering", "no");
        response.setHeader("Cache-Control", "no-cache, no-transform");
        return availability.connect(id);
    }
}
