package com.shipflow.webhook;

import com.shipflow.config.ShipFlowProperties;
import com.shipflow.observability.ShipFlowMetrics;
import com.shipflow.security.HmacSigner;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Delivers outbox events to merchant endpoints. Per poll: claim a batch (short autocommit statement, SKIP
 * LOCKED), POST each event outside any transaction (bounded parallelism), then record the result guarded by
 * the claim token. 2xx -> DELIVERED; otherwise retry with exponential backoff + jitter; after max attempts ->
 * DEAD + dead-letter row. Semantics: at-least-once; consumers dedupe on the event id (X-ShipFlow-Event-Id).
 */
@Component
public class WebhookDispatcher {
    private static final Logger log = LoggerFactory.getLogger(WebhookDispatcher.class);
    public static final String SIGNATURE_HEADER = "X-ShipFlow-Signature";
    public static final String EVENT_ID_HEADER = "X-ShipFlow-Event-Id";

    private final OutboxRepository outbox;
    private final WebhookTransport transport;
    private final ShipFlowProperties.Webhook props;
    private final BackoffPolicy backoff;
    private final ShipFlowMetrics metrics;
    private final Clock clock;
    private final ExecutorService workers = Executors.newVirtualThreadPerTaskExecutor();

    public WebhookDispatcher(OutboxRepository outbox, WebhookTransport transport, ShipFlowProperties props,
                             ShipFlowMetrics metrics, Clock clock) {
        this.outbox = outbox;
        this.transport = transport;
        this.props = props.webhook();
        this.backoff = new BackoffPolicy(this.props.baseBackoff(), this.props.maxBackoff(),
                () -> ThreadLocalRandom.current().nextDouble());
        this.metrics = metrics;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${shipflow.webhook.poll-interval}")
    public void poll() {
        try {
            while (dispatchOnce() == props.batchSize()) {
                // full batch: keep draining
            }
        } catch (RuntimeException e) {
            log.warn("webhook dispatch cycle failed: {}", e.toString());
        }
    }

    /** One claim + deliver cycle; returns number of events claimed. */
    public int dispatchOnce() {
        List<OutboxRepository.Claimed> batch = outbox.claimDue(props.batchSize(), clock.instant(), props.lease());
        if (batch.isEmpty()) {
            return 0;
        }
        List<Future<?>> futures = new ArrayList<>();
        java.util.concurrent.Semaphore permits = new java.util.concurrent.Semaphore(Math.max(1, props.workers()));
        for (OutboxRepository.Claimed e : batch) {
            futures.add(workers.submit(() -> {
                permits.acquireUninterruptibly();
                try {
                    deliver(e);
                } finally {
                    permits.release();
                }
            }));
        }
        for (Future<?> f : futures) {
            try {
                f.get();
            } catch (Exception ex) {
                log.warn("webhook worker failed: {}", ex.toString());
            }
        }
        return batch.size();
    }

    void deliver(OutboxRepository.Claimed e) {
        Instant started = clock.instant();
        long t0 = System.nanoTime();
        Integer status = null;
        String error = null;
        try {
            long ts = started.getEpochSecond();
            Map<String, String> headers = new LinkedHashMap<>();
            headers.put("Content-Type", "application/json");
            headers.put(EVENT_ID_HEADER, e.id().toString());
            headers.put("X-ShipFlow-Event-Type", e.eventType());
            headers.put("X-ShipFlow-Attempt", String.valueOf(e.attempt()));
            headers.put(SIGNATURE_HEADER, HmacSigner.signatureHeader(e.secret(), ts, e.payload()));
            status = transport.post(e.url(), headers, e.payload(), props.requestTimeout());
            if (status < 200 || status >= 300) {
                error = "HTTP " + status;
            }
        } catch (Exception ex) {
            error = ex.getClass().getSimpleName() + ": " + ex.getMessage();
        }
        long ms = (System.nanoTime() - t0) / 1_000_000;
        Instant now = clock.instant();
        outbox.logAttempt(e.id(), e.attempt(), e.claimToken(), status, error, ms, now);
        if (error == null) {
            outbox.markDelivered(e.id(), e.claimToken(), now);
            metrics.webhook("delivered");
        } else if (e.attempt() >= props.maxAttempts()) {
            outbox.markDead(e.id(), e.claimToken(), error, now);
            metrics.webhook("dead_lettered");
            log.warn("webhook dead-lettered event={} merchant={} attempts={} error={}", e.id(), e.merchantId(),
                    e.attempt(), error);
        } else {
            outbox.scheduleRetry(e.id(), e.claimToken(), now.plus(backoff.delayAfter(e.attempt())), error);
            metrics.webhook("retry_scheduled");
        }
    }

    @PreDestroy
    void shutdown() {
        workers.shutdown();
    }
}
