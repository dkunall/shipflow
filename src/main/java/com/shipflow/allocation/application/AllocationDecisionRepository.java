package com.shipflow.allocation.application;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class AllocationDecisionRepository {
    public record Reserved(long id, long carrierId, String zone, LocalDate serviceDate) {
    }

    public record Decision(long id, Long carrierId, String zone, LocalDate serviceDate, String outcome,
                           BigDecimal score, String explanationJson, String releaseReason, Instant createdAt,
                           Instant releasedAt) {
    }

    private final JdbcTemplate jdbc;

    public AllocationDecisionRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public long insert(UUID shipmentId, Long carrierId, String zone, LocalDate serviceDate, String outcome,
                       Double score, String explanationJson) {
        return jdbc.queryForObject("""
                INSERT INTO allocation_decision (shipment_id, carrier_id, zone_code, service_date, outcome, score, explanation)
                VALUES (?, ?, ?, ?, ?, ?, CAST(? AS jsonb)) RETURNING id
                """, Long.class, shipmentId, carrierId, zone, Date.valueOf(serviceDate), outcome,
                score == null ? null : BigDecimal.valueOf(score).setScale(6, java.math.RoundingMode.HALF_UP),
                explanationJson);
    }

    /** Flips the shipment's RESERVED decision to RELEASED exactly once; returns what to give back. */
    public Optional<Reserved> release(UUID shipmentId, String reason, Instant now) {
        return jdbc.query("""
                UPDATE allocation_decision SET outcome = 'RELEASED', release_reason = ?, released_at = ?
                 WHERE shipment_id = ? AND outcome = 'RESERVED'
                RETURNING id, carrier_id, zone_code, service_date
                """, (rs, i) -> new Reserved(rs.getLong(1), rs.getLong(2), rs.getString(3), rs.getDate(4).toLocalDate()),
                reason, Timestamp.from(now), shipmentId).stream().findFirst();
    }

    public List<Decision> findByShipment(UUID shipmentId) {
        return jdbc.query("""
                SELECT id, carrier_id, zone_code, service_date, outcome, score, explanation::text, release_reason,
                       created_at, released_at
                  FROM allocation_decision WHERE shipment_id = ? ORDER BY id
                """, (rs, i) -> new Decision(rs.getLong(1), (Long) rs.getObject(2), rs.getString(3),
                rs.getDate(4).toLocalDate(), rs.getString(5), rs.getBigDecimal(6), rs.getString(7), rs.getString(8),
                rs.getTimestamp(9).toInstant(), rs.getTimestamp(10) == null ? null : rs.getTimestamp(10).toInstant()),
                shipmentId);
    }
}
