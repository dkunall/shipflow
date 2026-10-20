package com.shipflow.webhook;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Transactional outbox: the event row is written in the SAME transaction as the state change, so an event
 * exists iff the change committed. Only merchants with a webhook endpoint get rows (single INSERT..SELECT).
 */
@Component
public class OutboxWriter {
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final Clock clock;

    public OutboxWriter(JdbcTemplate jdbc, ObjectMapper mapper, Clock clock) {
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.clock = clock;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void write(UUID merchantId, UUID aggregateId, String type, Map<String, Object> data) {
        UUID id = UUID.randomUUID();
        Instant now = clock.instant();
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("id", id.toString());
        envelope.put("type", type);
        envelope.put("createdAt", now.toString());
        envelope.put("data", data);
        String payload;
        try {
            payload = mapper.writeValueAsString(envelope);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        jdbc.update("""
                INSERT INTO outbox_event (id, merchant_id, aggregate_id, event_type, payload, status, attempts,
                                          next_attempt_at, created_at)
                SELECT ?, m.id, ?, ?, CAST(? AS jsonb), 'PENDING', 0, ?, ?
                  FROM merchant m WHERE m.id = ? AND m.webhook_url IS NOT NULL
                """, id, aggregateId, type, payload, Timestamp.from(now), Timestamp.from(now), merchantId);
    }
}
