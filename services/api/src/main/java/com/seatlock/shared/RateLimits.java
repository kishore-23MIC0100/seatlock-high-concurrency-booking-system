package com.seatlock.shared;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class RateLimits {
    private static final DefaultRedisScript<Long> INCREMENT = new DefaultRedisScript<>("""
        local count = redis.call('INCR', KEYS[1])
        if count == 1 then redis.call('EXPIRE', KEYS[1], ARGV[1]) end
        return count
        """, Long.class);
    private record Window(long expires, int count) {}
    private final ConcurrentHashMap<String, Window> local = new ConcurrentHashMap<>();
    private final AtomicLong useFallbackUntil = new AtomicLong();
    private final StringRedisTemplate redis;
    private final boolean enabled;

    RateLimits(StringRedisTemplate redis, @Value("${seatlock.redis.enabled}") boolean enabled) {
        this.redis = redis;
        this.enabled = enabled;
    }

    public void check(String bucket, Object subject, int limit, Duration window) {
        long now = System.currentTimeMillis();
        String key = "seatlock:rate:" + bucket + ":" + Store.digest(subject.toString());
        Long count = null;
        if (enabled && now >= useFallbackUntil.get()) {
            try {
                count = redis.execute(INCREMENT, List.of(key), Long.toString(window.toSeconds()));
            } catch (DataAccessException ex) {
                useFallbackUntil.set(now + 5000);
            }
        }
        if (count == null) {
            if (local.size() >= 20_000 && !local.containsKey(key)) {
                throw limited();
            }
            count = (long) local.compute(key, (ignored, existing) -> existing == null || existing.expires <= now
                ? new Window(now + window.toMillis(), 1)
                : new Window(existing.expires, existing.count + 1)).count;
        }
        if (count > limit) throw limited();
    }

    private ApiFault limited() {
        return new ApiFault(HttpStatus.TOO_MANY_REQUESTS, "RATE_LIMITED", "Too many requests. Try again shortly.");
    }

    @Scheduled(fixedDelay = 60000)
    void evict() {
        long now = System.currentTimeMillis();
        local.entrySet().removeIf(entry -> entry.getValue().expires <= now);
    }
}
