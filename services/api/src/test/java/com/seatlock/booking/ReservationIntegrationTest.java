package com.seatlock.booking;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import com.seatlock.shared.ApiFault;
import com.seatlock.shared.RateLimits;
import com.seatlock.shared.Store;
import com.seatlock.community.Community;
import com.seatlock.community.Outbox;
import com.seatlock.community.OutboxConsumer;
import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;

@SpringBootTest(properties = {
    "seatlock.seed=true", "seatlock.demo-payments=true", "seatlock.workers.enabled=false", "seatlock.redis.enabled=true",
    "spring.data.redis.url=redis://127.0.0.1:1", "server.servlet.session.cookie.secure=false"
})
class ReservationIntegrationTest {
    private static final String EVENT = "contention-test";
    private static PostgreSQLContainer<?> postgres;

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        String external = System.getenv("SEATLOCK_TEST_DATABASE_URL");
        if (external != null) {
            registry.add("spring.datasource.url", () -> external);
            registry.add("spring.datasource.username", () -> System.getenv("SEATLOCK_TEST_DATABASE_USER"));
            registry.add("spring.datasource.password", () -> System.getenv("SEATLOCK_TEST_DATABASE_PASSWORD"));
        } else {
            postgres = new PostgreSQLContainer<>("postgres:16.15-alpine");
            postgres.start();
            registry.add("spring.datasource.url", postgres::getJdbcUrl);
            registry.add("spring.datasource.username", postgres::getUsername);
            registry.add("spring.datasource.password", postgres::getPassword);
        }
    }

    @Autowired Store store;
    @Autowired Reservations holds;
    @Autowired PaymentOrders paymentOrders;
    @Autowired PaymentRefunds refunds;
    @Autowired CheckoutPayments checkout;
    @MockitoBean RazorpayClient gateway;
    @Autowired PaymentFulfillment fulfillment;
    @Autowired Tickets tickets;
    @Autowired RateLimits limits;
    @Autowired TransactionTemplate transactions;
    @Autowired Community community;
    @Autowired Outbox outbox;
    @Autowired OutboxConsumer inbox;
    private List<UUID> users;
    private UUID organizer;
    private List<Long> seats;

    @BeforeEach
    void fixture() {
        store.db.sql("TRUNCATE users,events,razorpay_webhooks RESTART IDENTITY CASCADE").update();
        when(gateway.configured()).thenReturn(true);
        when(gateway.mode()).thenReturn("TEST");
        when(gateway.publicKey()).thenReturn("rzp_test_Fixture");
        users = store.db.sql("""
            INSERT INTO users(email,display_name,password_hash)
            SELECT 'customer'||n||'@seatlock.test','Test customer','unused' FROM generate_series(1,1000) n
            RETURNING id
            """).query(UUID.class).list();
        organizer = store.db.sql("""
            INSERT INTO users(email,display_name,password_hash,role)
            VALUES('organizer@seatlock.test','Test organizer','unused','ORGANIZER') RETURNING id
            """).query(UUID.class).single();
        store.db.sql("INSERT INTO organizers(user_id,name,status) VALUES(:id,'Test','APPROVED')")
            .param("id", organizer).update();
        UUID venue = store.db.sql("""
            INSERT INTO venues(organizer_id,name,city,zone_id) VALUES(:id,'Test Venue','Test City','UTC') RETURNING id
            """).param("id", organizer).query(UUID.class).single();
        long section = store.db.sql("INSERT INTO sections(venue_id,name) VALUES(:id,'Floor') RETURNING id")
            .param("id", venue).query(Long.class).single();
        seats = store.db.sql("""
            INSERT INTO seats(venue_id,section_id,row_label,seat_number)
            SELECT :venue,:section,'A',n FROM generate_series(1,8) n RETURNING id
            """).param("venue", venue).param("section", section).query(Long.class).list();
        store.db.sql("""
            INSERT INTO events(id,organizer_id,venue_id,name,description,category,starts_at,ends_at,sale_starts_at,status)
            VALUES(:event,:organizer,:venue,'Load','Fixture','MUSIC',now()+interval '2h',now()+interval '3h',
              now()-interval '1h','PUBLISHED')
            """).param("event", EVENT).param("organizer", organizer).param("venue", venue).update();
        long tier = store.db.sql("""
            INSERT INTO pricing_tiers(event_id,label,price_minor) VALUES(:event,'Floor',199900) RETURNING id
            """).param("event", EVENT).query(Long.class).single();
        store.db.sql("""
            INSERT INTO event_seats(event_id,venue_id,seat_id,pricing_tier_id)
            SELECT :event,venue_id,id,:tier FROM seats WHERE venue_id=:venue
            """).param("event", EVENT).param("venue", venue).param("tier", tier).update();
    }

    @Test
    void thousandSimultaneousAttemptsProduceExactlyOneHoldAndOneTicket() throws Exception {
        var results = race(IntStream.range(0, 1000).mapToObj(i -> (Callable<Object>) () ->
            result(() -> holds.create(users.get(i), EVENT, List.of(seats.getFirst()), "same-test-key"))).toList());
        assertThat(results.stream().filter(Reservations.HoldView.class::isInstance)).hasSize(1);
        assertThat(results.stream().filter("SEAT_CONFLICT"::equals)).hasSize(999);
        var hold = (Reservations.HoldView) results.stream().filter(Reservations.HoldView.class::isInstance).findFirst().orElseThrow();
        var winner = store.db.sql("SELECT user_id FROM holds WHERE id=:id").param("id", hold.id()).query(UUID.class).single();
        var payment = pay(winner, hold.id());
        assertThat(payment.status()).isEqualTo("APPROVED");
        assertThat(count("holds")).isEqualTo(1);
        assertThat(count("tickets")).isEqualTo(1);
    }

    @Test
    void overlappingGroupsAreAtomic() throws Exception {
        List<Object> results = race(List.of(
            () -> result(() -> holds.create(users.get(0), EVENT, seats.subList(0, 2), "group-left")),
            () -> result(() -> holds.create(users.get(1), EVENT, List.of(seats.get(2), seats.get(1)), "group-right"))));
        assertThat(results.stream().filter(Reservations.HoldView.class::isInstance)).hasSize(1);
        assertThat(results).contains("SEAT_CONFLICT");
        assertThat(count("hold_items")).isEqualTo(2);
        assertThat(store.db.sql("SELECT count(*) FROM event_seats WHERE state='HELD'").query(Long.class).single()).isEqualTo(2);
    }

    @Test
    void sameKeyAcrossThreadsReplaysAndRejectsAChangedPayload() throws Exception {
        UUID user = users.getFirst();
        var results = race(IntStream.range(0, 24).mapToObj(i -> (Callable<UUID>) () ->
            holds.create(user, EVENT, i % 2 == 0 ? List.of(seats.get(1), seats.get(0)) : seats.subList(0, 2),
                "identical-request").id()).toList());
        assertThat(results.stream().distinct()).hasSize(1);
        assertThat(count("holds")).isEqualTo(1);
        assertThatThrownBy(() -> holds.create(user, EVENT, List.of(seats.getLast()), "identical-request"))
            .isInstanceOfSatisfying(ApiFault.class, ex -> assertThat(ex.code).isEqualTo("IDEMPOTENCY_MISMATCH"));
    }

    @Test
    void concurrentDisjointHoldsObserveTheAccountCap() throws Exception {
        UUID user = users.getFirst();
        var results = race(List.of(
            () -> result(() -> holds.create(user, EVENT, seats.subList(0, 4), "account-cap-a")),
            () -> result(() -> holds.create(user, EVENT, seats.subList(4, 8), "account-cap-b"))));
        assertThat(results).contains("SEAT_LIMIT");
        assertThat(count("hold_items")).isEqualTo(4);
    }

    @Test
    void capturedPaymentAfterExpiryQueuesARefundWithoutStealingAReassignedSeat() throws Exception {
        var original = holds.create(users.get(0), EVENT, seats.subList(0, 1), "first-hold");
        var order = order(users.get(0), original.id());
        var receipt = capture(order);
        expire(original.id());
        var outcomes = race(List.of(
            () -> holds.create(users.get(1), EVENT, seats.subList(0, 1), "second-hold"),
            () -> holds.expireNext(),
            () -> holds.expireNext(),
            () -> { fulfillment.settleCapture(order, receipt, "late-event"); return "applied"; }
        ));
        var replacement = (Reservations.HoldView) outcomes.getFirst();
        assertThat(holds.view(users.get(0), original.id()).status()).isEqualTo("EXPIRED");
        assertThat(holds.view(users.get(1), replacement.id()).status()).isEqualTo("ACTIVE");
        assertThat(fulfillment.attempts(original.id()).getFirst().status()).isEqualTo("REFUND_PENDING");
        assertThat(store.db.sql("SELECT hold_id FROM event_seats WHERE seat_id=:id").param("id", seats.getFirst())
            .query(UUID.class).single()).isEqualTo(replacement.id());
        assertThat(count("tickets")).isZero();
        finishRefund();
        assertThat(fulfillment.attempts(original.id()).getFirst().status()).isEqualTo("REFUNDED");
        assertThat(count("tickets")).isZero();
    }

    @Test
    void concurrentBrowserAndWebhookDeliveryProduceExactlyOneBooking() throws Exception {
        UUID user = users.getFirst();
        var hold = holds.create(user, EVENT, seats.subList(0, 1), "payable-hold");
        var order = order(user, hold.id());
        var receipt = capture(order);
        race(List.of(
            () -> { fulfillment.settleCapture(order, receipt, "capture-event"); return 1; },
            () -> { fulfillment.settleCapture(order, receipt, "capture-event"); return 2; },
            () -> { fulfillment.settleCapture(order, receipt, null); return 3; }
        ));
        assertThat(count("bookings")).isEqualTo(1);
        assertThat(count("tickets")).isEqualTo(1);
        assertThat(count("razorpay_webhooks")).isEqualTo(1);
        assertThat(fulfillment.attempts(hold.id())).extracting(PaymentFulfillment.PaymentView::status).containsExactly("APPROVED");
    }

    @Test
    void readsServerTotalsAndReusesOneOrderAcrossCheckoutRetries() {
        UUID user = users.getFirst();
        var hold = holds.create(user, EVENT, seats.subList(0, 2), "order-reuse");
        var remote = new RazorpayClient.Order("order_testorder123", hold.id().toString(), hold.totalMinor(), "INR");
        when(gateway.createOrder(hold.id(), hold.totalMinor())).thenReturn(remote);
        var first = checkout.begin(user, hold.id());
        var retry = checkout.begin(user, hold.id());
        assertThat(first.orderId()).isEqualTo(retry.orderId()).isEqualTo(remote.id());
        assertThat(first.amountMinor()).isEqualTo(420600);
        assertThat(first.timeoutSeconds()).isPositive();
        org.mockito.Mockito.verify(gateway).createOrder(hold.id(), hold.totalMinor());
        assertThat(result(() -> checkout.begin(users.get(1), hold.id()))).isEqualTo("NOT_FOUND");
    }

    @Test
    void simultaneousDemoPaymentsCreateOnePaymentAndOneBooking() throws Exception {
        store.db.sql("UPDATE events SET demo=true WHERE id=:id").param("id", EVENT).update();
        UUID user = users.getFirst();
        var hold = holds.create(user, EVENT, List.of(seats.getFirst()), "demo-purchase");
        assertThat(checkout.status(user, hold.id()).mode()).isEqualTo("DEMO");
        assertThat(result(() -> checkout.begin(user, hold.id()))).isEqualTo("DEMO_PAYMENT_REQUIRED");
        assertThat(result(() -> checkout.completeDemo(users.get(1), hold.id()))).isEqualTo("NOT_FOUND");
        List<CheckoutPayments.State> results = race(List.of(
            () -> checkout.completeDemo(user, hold.id()), () -> checkout.completeDemo(user, hold.id())));
        UUID bookingId = results.getFirst().bookingId();
        assertThat(bookingId).isNotNull();
        assertThat(results).allSatisfy(state -> {
            assertThat(state.status()).isEqualTo("APPROVED");
            assertThat(state.bookingId()).isEqualTo(bookingId);
        });
        assertThat(fulfillment.attempts(hold.id())).singleElement().satisfies(payment -> {
            assertThat(payment.provider()).isEqualTo("SEATLOCK_DEMO");
            assertThat(payment.amountMinor()).isEqualTo(hold.totalMinor());
            assertThat(payment.id()).isEqualTo(tickets.booking(user, bookingId).paymentId());
        });
        assertThat(count("razorpay_orders")).isZero();
        assertThat(count("tickets")).isEqualTo(1);
    }

    @Test
    void demoPaymentsRejectRegularEventsExistingOrdersAndExpiredHolds() {
        UUID user = users.getFirst();
        var existing = holds.create(user, EVENT, List.of(seats.getFirst()), "regular-checkout");
        assertThat(result(() -> checkout.completeDemo(user, existing.id()))).isEqualTo("NOT_FOUND");
        order(user, existing.id());
        store.db.sql("UPDATE events SET demo=true WHERE id=:id").param("id", EVENT).update();
        assertThat(checkout.status(user, existing.id()).mode()).isEqualTo("TEST");
        assertThat(result(() -> checkout.completeDemo(user, existing.id()))).isEqualTo("PAYMENT_ORDER_EXISTS");
        var expired = holds.create(user, EVENT, List.of(seats.get(1)), "expired-demo");
        expire(expired.id());
        assertThat(result(() -> checkout.completeDemo(user, expired.id()))).isEqualTo("HOLD_INACTIVE");
        assertThat(count("payments")).isZero();
        assertThat(count("tickets")).isZero();
    }

    @Test
    void authorisationAndInvalidProofDoNotIssueTickets() {
        UUID user = users.getFirst();
        var hold = holds.create(user, EVENT, seats.subList(0, 1), "payment-proof");
        var order = order(user, hold.id());
        var captured = capture(order);
        assertThat(result(() -> checkout.verify(user, hold.id(), order.orderId(), captured.id(), "signature"))).isEqualTo("FORBIDDEN");
        when(gateway.checkoutSignature(order.orderId(), captured.id(), "signature")).thenReturn(true);
        when(gateway.payment(captured.id())).thenReturn(new RazorpayClient.Payment(captured.id(), order.orderId(),
            "authorized", hold.totalMinor(), "INR", false, 0));
        assertThat(checkout.verify(user, hold.id(), order.orderId(), captured.id(), "signature").status()).isEqualTo("AWAITING_PAYMENT");
        assertThat(count("tickets")).isZero();
        when(gateway.payment(captured.id())).thenReturn(new RazorpayClient.Payment(captured.id(), order.orderId(),
            "captured", hold.totalMinor() - 100, "INR", true, 0));
        assertThat(result(() -> checkout.verify(user, hold.id(), order.orderId(), captured.id(), "signature"))).isEqualTo("PAYMENT_MISMATCH");
        assertThat(count("tickets")).isZero();
        when(gateway.payment(captured.id())).thenReturn(captured);
        assertThat(checkout.verify(user, hold.id(), order.orderId(), captured.id(), "signature").bookingId()).isNotNull();
        assertThat(count("tickets")).isEqualTo(1);
    }

    @Test
    void refundVoidsOldTicketBeforeASeatCanBeSoldAgain() {
        UUID first = users.get(0);
        var hold = holds.create(first, EVENT, seats.subList(0, 1), "refund-hold");
        var booking = pay(first, hold.id()).bookingId();
        UUID voidTicket = tickets.booking(first, booking).tickets().getFirst().id();
        assertThat(tickets.refund(users.getLast(), booking, "Customer request").status()).isEqualTo("REFUND_PENDING");
        assertThat(tickets.get(first, voidTicket).state()).isEqualTo("REFUND_PENDING");
        assertThat(result(() -> holds.create(users.get(1), EVENT, seats.subList(0, 1), "before-refund")))
            .isEqualTo("SEAT_CONFLICT");
        finishRefund();
        UUID replacement = users.get(1);
        var next = holds.create(replacement, EVENT, seats.subList(0, 1), "refund-rebook");
        pay(replacement, next.id());
        assertThatThrownBy(() -> store.db.sql("UPDATE tickets SET state='VALID' WHERE id=:id")
            .param("id", voidTicket).update()).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void checkInIsScopedAndAllowsOneScan() throws Exception {
        UUID user = users.getFirst();
        var hold = holds.create(user, EVENT, seats.subList(0, 1), "checkin-hold");
        var booking = pay(user, hold.id()).bookingId();
        UUID id = tickets.booking(user, booking).tickets().getFirst().id();
        String code = "SL1." + store.db.sql("SELECT qr_token FROM tickets WHERE id=:id").param("id", id).query(UUID.class).single();
        assertThat(result(() -> tickets.checkIn(users.getLast(), code))).isEqualTo("NOT_FOUND");
        var results = race(List.of(() -> result(() -> tickets.checkIn(organizer, code)), () -> result(() -> tickets.checkIn(organizer, code))));
        assertThat(results).contains("ALREADY_CHECKED_IN");
        assertThat(count("ticket_checkins")).isEqualTo(1);
    }

    @Test
    void redisOutageFallsBackWithoutAffectingBooking() {
        UUID user = users.getFirst();
        limits.check("outage-test", user, 1, Duration.ofMinutes(1));
        assertThatThrownBy(() -> limits.check("outage-test", user, 1, Duration.ofMinutes(1)))
            .isInstanceOfSatisfying(ApiFault.class, ex -> assertThat(ex.code).isEqualTo("RATE_LIMITED"));
        assertThat(holds.create(user, EVENT, seats.subList(0, 1), "fallback-key").status()).isEqualTo("ACTIVE");
    }

    @Test
    void boundedLockWaitRollsBackTheIdempotencyClaim() throws Exception {
        var locked = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<?> holder = executor.submit(() -> transactions.executeWithoutResult(tx -> {
                holds.lockSeats(EVENT, seats.subList(0, 1));
                locked.countDown();
                try {
                    if (!release.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("Seat lock did not release");
                } catch (InterruptedException error) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(error);
                }
            }));
            try {
                assertThat(locked.await(5, TimeUnit.SECONDS)).isTrue();
                assertThatThrownBy(() -> holds.create(users.getFirst(), EVENT, seats.subList(0, 1), "bounded-wait"))
                    .isInstanceOf(org.springframework.dao.DataAccessException.class);
                assertThat(count("idempotency_keys")).isZero();
            } finally {
                release.countDown();
            }
            holder.get(5, TimeUnit.SECONDS);
        }
        assertThat(holds.create(users.getFirst(), EVENT, seats.subList(0, 1), "bounded-wait").status()).isEqualTo("ACTIVE");
    }

    @Test
    void transactionalWorkerCanReplayNotificationsAcrossADeliveryRetry() throws Exception {
        UUID user = users.getFirst();
        var hold = holds.create(user, EVENT, seats.subList(0, 1), "outbox-hold");
        pay(user, hold.id());
        long message = store.db.sql("SELECT id FROM outbox_events WHERE event_type='BOOKING_CONFIRMED'")
            .query(Long.class).single();
        assertThat(community.notices(user)).isEmpty();
        race(List.of(() -> { inbox.process(message); return 1; }, () -> { inbox.process(message); return 2; }));
        assertThat(community.notices(user)).hasSize(1);
        while (outbox.publishNext()) {
            // Replay the committed message as a restarted relay would.
        }
        assertThat(count("processed_events")).isEqualTo(2);
        assertThat(community.notices(user)).hasSize(1);
    }

    @Test
    void waitingRoomAdmitsQueuedUsersInOrderAndCannotBeSkipped() {
        store.db.sql("UPDATE events SET room_capacity=1 WHERE id=:id").param("id", EVENT).update();
        assertThat(result(() -> holds.create(users.get(1), EVENT, seats.subList(0, 1), "queue-blocked")))
            .isEqualTo("ADMISSION_REQUIRED");
        assertThat(community.enter(users.get(0), EVENT).status()).isEqualTo("ADMITTED");
        assertThat(community.enter(users.get(1), EVENT).position()).isEqualTo(1L);
        assertThat(community.enter(users.get(2), EVENT).position()).isEqualTo(2L);
        assertThat(community.enter(users.get(1), EVENT).position()).isEqualTo(1L);
        store.db.sql("UPDATE admission_tokens SET expires_at=now()-interval '1s' WHERE user_id=:user")
            .param("user", users.get(0)).update();
        community.advanceRoom(EVENT);
        assertThat(community.admission(users.get(1), EVENT).status()).isEqualTo("ADMITTED");
        assertThat(community.admission(users.get(2), EVENT).position()).isEqualTo(1L);
        assertThat(community.enter(users.get(0), EVENT).position()).isEqualTo(2L);
    }

    @Test
    void twoWaitlistWorkersCanOnlyOfferTheReleasedSeatToTheFirstCustomer() throws Exception {
        store.db.sql("UPDATE event_seats SET state='BLOCKED' WHERE seat_id<>:seat")
            .param("seat", seats.getFirst()).update();
        var hold = holds.create(users.get(0), EVENT, seats.subList(0, 1), "waitlist-hold");
        var first = community.join(users.get(1), EVENT, 1, false);
        var next = community.join(users.get(2), EVENT, 1, false);
        assertThat(first.position()).isEqualTo(1L);
        assertThat(next.position()).isEqualTo(2L);
        holds.cancel(users.get(0), hold.id());
        assertThat(race(List.of(() -> community.offerNext(EVENT), () -> community.offerNext(EVENT))))
            .containsExactlyInAnyOrder(true, false);
        assertThat(community.entries(users.get(1)).getFirst().state()).isEqualTo("OFFERED");
        assertThat(community.entries(users.get(2)).getFirst().state()).isEqualTo("QUEUED");
        var offered = community.entries(users.get(1)).getFirst();
        community.leave(users.get(1), offered.id());
        assertThat(community.offerNext(EVENT)).isTrue();
        assertThat(community.entries(users.get(2)).getFirst().state()).isEqualTo("OFFERED");
    }

    @Test
    void postgresDeadlockVictimRollsBackAndNormalHoldsRecover() throws Exception {
        var bothLocked = new java.util.concurrent.CyclicBarrier(2);
        Callable<String> forward = () -> lockInTestOrder(seats.get(0), seats.get(1), bothLocked);
        Callable<String> reverse = () -> lockInTestOrder(seats.get(1), seats.get(0), bothLocked);
        assertThat(race(List.of(forward, reverse))).containsExactlyInAnyOrder("committed", "40P01");
        assertThat(holds.create(users.getFirst(), EVENT, seats.subList(0, 2), "deadlock-recovery").seats()).hasSize(2);
    }

    private String lockInTestOrder(long first, long second, java.util.concurrent.CyclicBarrier barrier) {
        try {
            transactions.executeWithoutResult(tx -> {
                holds.lockSeats(EVENT, List.of(first));
                try {
                    barrier.await(5, TimeUnit.SECONDS);
                } catch (Exception error) {
                    throw new IllegalStateException(error);
                }
                holds.lockSeats(EVENT, List.of(second));
            });
            return "committed";
        } catch (org.springframework.dao.DataAccessException exception) {
            if (exception.getMostSpecificCause() instanceof SQLException sql) return sql.getSQLState();
            throw exception;
        }
    }

    private PaymentFulfillment.PaymentView pay(UUID user, UUID hold) {
        var order = order(user, hold);
        fulfillment.settleCapture(order, capture(order), null);
        return fulfillment.attempts(hold).getFirst();
    }

    private PaymentOrders.Order order(UUID user, UUID hold) {
        var claim = paymentOrders.claim(user, hold, "TEST").order();
        return paymentOrders.attach(claim, new RazorpayClient.Order("order_" + token(),
            hold.toString(), claim.amountMinor(), "INR"));
    }

    private static RazorpayClient.Payment capture(PaymentOrders.Order order) {
        return new RazorpayClient.Payment("pay_" + token(), order.orderId(), "captured", order.amountMinor(), "INR", true, 0);
    }

    private void finishRefund() {
        var job = refunds.claim("TEST").getFirst();
        refunds.complete(job, new RazorpayClient.Refund("rfnd_" + token(), job.paymentReference(), "processed", job.amount()));
    }

    private static String token() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    private void expire(UUID hold) {
        transactions.executeWithoutResult(tx -> {
            store.db.sql("UPDATE holds SET expires_at=clock_timestamp()-interval '1s' WHERE id=:id")
                .param("id", hold).update();
            store.db.sql("UPDATE event_seats SET expires_at=clock_timestamp()-interval '1s' WHERE hold_id=:id")
                .param("id", hold).update();
        });
    }

    private long count(String table) {
        return store.db.sql("SELECT count(*) FROM " + table).query(Long.class).single();
    }

    private static Object result(Supplier<?> action) {
        try {
            return action.get();
        } catch (ApiFault fault) {
            return fault.code;
        }
    }

    private static <T> List<T> race(List<Callable<T>> actions) throws Exception {
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var ready = new CountDownLatch(actions.size());
            var start = new CountDownLatch(1);
            var tasks = new ArrayList<Future<T>>();
            for (var action : actions) {
                tasks.add(executor.submit(() -> {
                    ready.countDown();
                    if (!start.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("Start barrier timed out");
                    return action.call();
                }));
            }
            boolean allReady = ready.await(10, TimeUnit.SECONDS);
            start.countDown();
            assertThat(allReady).isTrue();
            List<T> result = new ArrayList<>();
            for (var task : tasks) result.add(task.get(30, TimeUnit.SECONDS));
            return result;
        }
    }
}
