package com.shipflow.shipment.persistence;

import com.shipflow.observability.Correlation;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Append-only status audit trail. */
@Repository
public class ShipmentHistoryRepository {
    public record HistoryEntry(String fromStatus, String toStatus, String reason, String actor, boolean inferred,
                               String location, Instant occurredAt) {
    }

    private final JdbcTemplate jdbc;

    public ShipmentHistoryRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void append(UUID shipmentId, String from, String to, String reason, String actor, boolean inferred,
                       String location, Instant occurredAt) {
        jdbc.update("""
                INSERT INTO shipment_status_history (shipment_id, from_status, to_status, reason, actor, inferred,
                                                     location, occurred_at, correlation_id)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, shipmentId, from, to, truncate(reason, 300), actor, inferred, location, Timestamp.from(occurredAt),
                Correlation.currentId());
    }

    public List<HistoryEntry> findByShipment(UUID shipmentId) {
        return jdbc.query("""
                SELECT from_status, to_status, reason, actor, inferred, location, occurred_at
                  FROM shipment_status_history WHERE shipment_id = ? ORDER BY id
                """, (rs, i) -> new HistoryEntry(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4),
                rs.getBoolean(5), rs.getString(6), rs.getTimestamp(7).toInstant()), shipmentId);
    }

    static String truncate(String s, int max) {
        return s == null || s.length() <= max ? s : s.substring(0, max);
    }
}
