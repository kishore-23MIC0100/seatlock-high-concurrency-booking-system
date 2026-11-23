package com.seatlock.community;

import com.seatlock.shared.Store;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class Outbox {
    private final Store store;
    private final OutboxConsumer inbox;
    private final RabbitTemplate rabbit;
    private final boolean enabled;
    private final MeterRegistry metrics;

    Outbox(Store store, OutboxConsumer inbox, RabbitTemplate rabbit, MeterRegistry metrics,
           @Value("${seatlock.rabbit.enabled}") boolean enabled) {
        this.store = store;
        this.inbox = inbox;
        this.rabbit = rabbit;
        this.enabled = enabled;
        this.metrics = metrics;
    }

    @Transactional
    public boolean publishNext() {
        store.boundLocks();
        Long id = store.db.sql("""
            SELECT id FROM outbox_events WHERE published_at IS NULL AND failed_at IS NULL AND next_attempt_at<=clock_timestamp()
            ORDER BY next_attempt_at,id LIMIT 1 FOR UPDATE SKIP LOCKED
            """).query(Long.class).optional().orElse(null);
        if (id == null) return false;

        if (enabled) {
            String failure = publish(id);
            if (failure != null) {
                store.db.sql("""
                    UPDATE outbox_events SET attempts=attempts+1,last_error=:reason,
                      next_attempt_at=clock_timestamp()+make_interval(secs=>power(2,attempts)),
                      failed_at=CASE WHEN attempts+1>=6 THEN clock_timestamp() END
                    WHERE id=:id
                    """).param("reason", failure).param("id", id).update();
                metrics.counter("seatlock.worker.failures", "worker", "outbox").increment();
                return false;
            }
        } else {
            inbox.process(id);
        }
        store.db.sql("UPDATE outbox_events SET published_at=clock_timestamp(),last_error=NULL WHERE id=:id")
            .param("id", id).update();
        return true;
    }

    private String publish(long id) {
        String message = Long.toString(id);
        var correlation = new CorrelationData();
        try {
            rabbit.convertAndSend(RabbitEvents.EXCHANGE, RabbitEvents.QUEUE, message, correlation);
            var confirmation = correlation.getFuture().get(2, TimeUnit.SECONDS);
            return confirmation.isAck() && correlation.getReturned() == null ? null : "BROKER_REJECTED";
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            return "INTERRUPTED";
        } catch (TimeoutException error) {
            return "CONFIRM_TIMEOUT";
        } catch (ExecutionException | AmqpException error) {
            return "BROKER_UNAVAILABLE";
        }
    }

    public Long queueDepth(String queue) {
        if (!enabled) return null;
        try {
            return rabbit.execute(channel -> channel.messageCount(queue));
        } catch (AmqpException | IllegalStateException error) {
            return null;
        }
    }
}
