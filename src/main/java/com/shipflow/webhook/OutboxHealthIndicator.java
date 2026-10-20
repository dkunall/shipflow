package com.shipflow.webhook;

import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

/** Webhook backlog / dead letters as health details (stays UP: a merchant outage must not fail liveness). */
@Component("webhookOutbox")
public class OutboxHealthIndicator implements HealthIndicator {
    private final OutboxRepository outbox;

    public OutboxHealthIndicator(OutboxRepository outbox) {
        this.outbox = outbox;
    }

    @Override
    public Health health() {
        long pending = outbox.count("PENDING");
        long dead = outbox.count("DEAD");
        return Health.up().withDetail("pending", pending).withDetail("dead", dead).withDetail("attention", dead > 0)
                .build();
    }
}
