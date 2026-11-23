package com.seatlock.booking;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.OffsetDateTime;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ReservationRulesTest {
    private final OffsetDateTime expiry = OffsetDateTime.parse("2026-11-23T08:30:00Z");

    @Test
    void exactExpiryEndsTheHoldAndMakesItsSeatEligibleForReassignment() {
        var id = UUID.randomUUID();
        var hold = new Reservations.Hold(id, UUID.randomUUID(), "concert", "ACTIVE", expiry.minusMinutes(10), expiry, 399800, 14900, 5900);
        var seat = new Reservations.LockedSeat(42, "HELD", id, expiry, 199900);
        assertThat(hold.activeAt(expiry.minusNanos(1))).isTrue();
        assertThat(hold.activeAt(expiry)).isFalse();
        assertThat(seat.availableAt(expiry.minusNanos(1))).isFalse();
        assertThat(seat.availableAt(expiry)).isTrue();
        assertThat(hold.total()).isEqualTo(420600L);
    }

    @Test
    void inventoryStatesAndFinalHoldsNeverReopenFromTheClock() {
        var id = UUID.randomUUID();
        var hold = new Reservations.Hold(id, UUID.randomUUID(), "concert", "CONVERTED", expiry.minusMinutes(10), expiry, 0, 0, 0);
        assertThat(hold.activeAt(expiry.minusMinutes(1))).isFalse();
        for (String state : new String[] {"BLOCKED", "SOLD"}) {
            assertThat(new Reservations.LockedSeat(42, state, null, null, 0).availableAt(expiry)).isFalse();
        }
    }
}
