package com.shipflow.idempotency;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Plain JDBC; UNIQUE (scope, idem_key) is what serialises concurrent retries of the same key. */
@Repository
public class IdempotencyRepository {
    public record IdempotencyRecord(long id, String operation, String requestHash, String status, UUID resourceId,
                                    Integer responseStatus) {
        boolean completed() {
            return "COMPLETED".equals(status);
        }
    }

    private final NamedParameterJdbcTemplate jdbc;

    public IdempotencyRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<Long> tryClaim(String scope, String key, String op, String hash, Instant now, Instant expiresAt) {
        List<Long> ids = jdbc.queryForList("""
                INSERT INTO idempotency_record (scope, idem_key, operation, request_hash, status, created_at, updated_at, expires_at)
                VALUES (:s, :k, :op, :h, 'IN_PROGRESS', :now, :now, :exp)
                ON CONFLICT (scope, idem_key) DO NOTHING
                RETURNING id
                """, p(scope, key).addValue("op", op).addValue("h", hash)
                .addValue("now", Timestamp.from(now)).addValue("exp", Timestamp.from(expiresAt)), Long.class);
        return ids.stream().findFirst();
    }

    /** Take over an IN_PROGRESS claim abandoned by a crashed process (same payload only). */
    public Optional<Long> reclaimStale(String scope, String key, String hash, Instant staleBefore, Instant now) {
        List<Long> ids = jdbc.queryForList("""
                UPDATE idempotency_record SET updated_at = :now
                 WHERE scope = :s AND idem_key = :k AND request_hash = :h
                   AND status = 'IN_PROGRESS' AND updated_at < :stale
                RETURNING id
                """, p(scope, key).addValue("h", hash).addValue("stale", Timestamp.from(staleBefore))
                .addValue("now", Timestamp.from(now)), Long.class);
        return ids.stream().findFirst();
    }

    public Optional<IdempotencyRecord> find(String scope, String key) {
        return jdbc.query("""
                SELECT id, operation, request_hash, status, resource_id, response_status
                  FROM idempotency_record WHERE scope = :s AND idem_key = :k
                """, p(scope, key), (rs, i) -> new IdempotencyRecord(rs.getLong("id"), rs.getString("operation"),
                rs.getString("request_hash"), rs.getString("status"), rs.getObject("resource_id", UUID.class),
                (Integer) rs.getObject("response_status"))).stream().findFirst();
    }

    public void complete(long id, UUID resourceId, int status, Instant now) {
        jdbc.update("""
                UPDATE idempotency_record SET status = 'COMPLETED', resource_id = :r, response_status = :st, updated_at = :now
                 WHERE id = :id
                """, new MapSqlParameterSource("id", id).addValue("r", resourceId).addValue("st", status)
                .addValue("now", Timestamp.from(now)));
    }

    public void release(long id) {
        jdbc.update("DELETE FROM idempotency_record WHERE id = :id AND status = 'IN_PROGRESS'",
                new MapSqlParameterSource("id", id));
    }

    public void deleteExpired(String scope, String key, Instant now) {
        jdbc.update("DELETE FROM idempotency_record WHERE scope = :s AND idem_key = :k AND expires_at < :now",
                p(scope, key).addValue("now", Timestamp.from(now)));
    }

    public int deleteAllExpired(Instant now) {
        return jdbc.update("DELETE FROM idempotency_record WHERE expires_at < :now",
                new MapSqlParameterSource("now", Timestamp.from(now)));
    }

    private static MapSqlParameterSource p(String scope, String key) {
        return new MapSqlParameterSource("s", scope).addValue("k", key);
    }
}
