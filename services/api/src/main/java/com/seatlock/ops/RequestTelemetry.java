package com.seatlock.ops;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
@Order(-150)
class RequestTelemetry extends OncePerRequestFilter {
    record Sample(long timestamp, long micros, int status) {}
    record Bucket(Instant startsAt, int requests, int clientErrors, int serverErrors, Double p95Ms) {}
    record Window(int requests, double requestsPerSecond, int clientErrors, int serverErrors, Double p50Ms,
                  Double p95Ms, Double p99Ms, List<Bucket> trend, boolean capped) {}

    private static final long WINDOW = 60_000;
    private static final int MAX_SAMPLES = 30_000;
    private final ArrayDeque<Sample> samples = new ArrayDeque<>();
    private long capReachedAt;

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        return !path.startsWith("/api/v1/") || path.startsWith("/api/v1/ops/") || path.endsWith("/availability/stream");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        long start = System.nanoTime();
        try {
            chain.doFilter(request, response);
        } finally {
            add(TimeUnit.NANOSECONDS.toMicros(System.nanoTime() - start), response.getStatus());
        }
    }

    private synchronized void add(long micros, int status) {
        long now = System.currentTimeMillis();
        evict(now);
        if (samples.size() == MAX_SAMPLES) {
            samples.removeFirst();
            capReachedAt = now;
        }
        samples.addLast(new Sample(now, micros, status));
    }

    Window window() {
        long now;
        List<Sample> recent;
        boolean capped;
        synchronized (this) {
            now = System.currentTimeMillis();
            evict(now);
            recent = List.copyOf(samples);
            capped = now - capReachedAt < WINDOW;
        }
        List<Bucket> trend = new ArrayList<>();
        for (int i = 6; i > 0; i--) {
            long start = now - i * 10_000L;
            List<Sample> bucket = recent.stream().filter(s -> s.timestamp >= start && s.timestamp < start + 10_000).toList();
            trend.add(new Bucket(Instant.ofEpochMilli(start), bucket.size(), errors(bucket, 4), errors(bucket, 5),
                percentile(latencies(bucket), .95)));
        }
        long[] values = latencies(recent);
        return new Window(recent.size(), recent.size() / 60.0, errors(recent, 4), errors(recent, 5),
            percentile(values, .50), percentile(values, .95), percentile(values, .99), trend, capped);
    }

    private void evict(long now) {
        while (!samples.isEmpty() && samples.peekFirst().timestamp < now - WINDOW) samples.removeFirst();
    }

    private static int errors(List<Sample> samples, int family) {
        return (int) samples.stream().filter(sample -> sample.status / 100 == family).count();
    }

    private static long[] latencies(List<Sample> samples) {
        long[] values = samples.stream().mapToLong(Sample::micros).toArray();
        Arrays.sort(values);
        return values;
    }

    private static Double percentile(long[] samples, double percentile) {
        if (samples.length == 0) return null;
        return samples[(int) Math.ceil(samples.length * percentile) - 1] / 1000.0;
    }
}
