package com.shipflow.webhook;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Repository
public class OutboxRepository {
    public record Claimed(UUID id, UUID merchantId, String eventType, String payload, int attempt, UUID claimToken,
                          String url, String secret) {
    }

    private final JdbcTemplate jdbc;

    public OutboxRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Claims due events in one statement: FOR UPDATE SKIP LOCKED makes concurrent dispatchers (threads or
     * instances) pick disjoint rows; the lease (next_attempt_at pushed forward) + claim token make a crashed
     * dispatcher's events reappear later (at-least-once) and stop a stale worker from overwriting results.
     */
    public List<Claimed> claimDue(int batch, Instant now, Duration lease) {
        UUID token = UUID.randomUUID();
        return jdbc.query("""
                UPDATE outbox_event o
                   SET claim_token = ?, attempts = o.attempts + 1, next_attempt_at = ?
                  FROM merchant m
                 WHERE m.id = o.merchant_id
                   AND o.id IN (SELECT id FROM outbox_event
                                 WHERE status = 'PENDING' AND next_attempt_at <= ?
                                 ORDER BY next_attempt_at
                                 LIMIT ? FOR UPDATE SKIP LOCKED)
                RETURNING o.id, o.merchant_id, o.event_type, o.payload::text, o.attempts, o.claim_token,
                          m.webhook_url, m.webhook_secret
                """, (rs, i) -> new Claimed(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getString(3),
                rs.getString(4), rs.getInt(5), rs.getObject(6, UUID.class), rs.getString(7), rs.getString(8)),
                token, Timestamp.from(now.plus(lease)), Timestamp.from(now), batch);
    }

    public boolean markDelivered(UUID id, UUID token, Instant now) {
        return jdbc.update("""
                UPDATE outbox_event SET status = 'DELIVERED', delivered_at = ?, claim_token = NULL, last_error = NULL
                 WHERE id = ? AND claim_token = ? AND status = 'PENDING'
                """, Timestamp.from(now), id, token) == 1;
    }

    public boolean scheduleRetry(UUID id, UUID token, Instant nextAt, String error) {
        return jdbc.update("""
                UPDATE outbox_event SET next_attempt_at = ?, claim_token = NULL, last_error = ?
                 WHERE id = ? AND claim_token = ? AND status = 'PENDING'
                """, Timestamp.from(nextAt), truncate(error), id, token) == 1;
    }

    public boolean markDead(UUID id, UUID token, String error, Instant now) {
        int n = jdbc.update("""
                UPDATE outbox_event SET status = 'DEAD', claim_token = NULL, last_error = ?
                 WHERE id = ? AND claim_token = ? AND status = 'PENDING'
                """, truncate(error), id, token);
        if (n == 1) {
            jdbc.update("""
                    INSERT INTO webhook_dead_letter (event_id, merchant_id, event_type, attempts, last_error, dead_at)
                    SELECT id, merchant_id, event_type, attempts, last_error, ? FROM outbox_event WHERE id = ?
                    ON CONFLICT (event_id) DO UPDATE SET attempts = EXCLUDED.attempts, last_error = EXCLUDED.last_error,
                                                         dead_at = EXCLUDED.dead_at, replayed_at = NULL
                    """, Timestamp.from(now), id);
        }
        return n == 1;
    }

    public void logAttempt(UUID eventId, int attemptNo, UUID token, Integer httpStatus, String error, long durationMs,
                           Instant at) {
        jdbc.update("""
                INSERT INTO webhook_attempt (event_id, attempt_no, claim_token, http_status, error, duration_ms, attempted_at)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """, eventId, attemptNo, token, httpStatus, truncate(error), (int) durationMs, Timestamp.from(at));
    }

    public boolean replayDead(UUID eventId, Instant now) {
        int n = jdbc.update("""
                UPDATE outbox_event SET status = 'PENDING', attempts = 0, next_attempt_at = ?, last_error = NULL
                 WHERE id = ? AND status = 'DEAD'
                """, Timestamp.from(now), eventId);
        if (n == 1) {
            jdbc.update("UPDATE webhook_dead_letter SET replayed_at = ? WHERE event_id = ?", Timestamp.from(now), eventId);
        }
        return n == 1;
    }

    public long count(String status) {
        Long n = jdbc.queryForObject("SELECT count(*) FROM outbox_event WHERE status = ?", Long.class, status);
        return n == null ? 0 : n;
    }

    private static String truncate(String s) {
        return s == null || s.length() <= 500 ? s : s.substring(0, 500);
    }
}
