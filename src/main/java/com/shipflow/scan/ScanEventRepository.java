package com.shipflow.scan;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Repository
public class ScanEventRepository {
    public record StoredScan(String carrierEventId, String eventCode, Instant occurredAt, Instant receivedAt,
                             String location, String ndrReason, String source, String outcome, String detail) {
    }

    private final JdbcTemplate jdbc;

    public ScanEventRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Insert-if-absent keyed by (carrier, carrier_event_id); false = duplicate delivery of the same event. */
    public boolean insert(UUID shipmentId, long carrierId, ScanCommand c, String source, ScanOutcome outcome,
                          String detail, Instant receivedAt) {
        return !jdbc.queryForList("""
                INSERT INTO scan_event (shipment_id, carrier_id, carrier_event_id, event_code, occurred_at, received_at,
                                        location, ndr_reason, source, outcome, detail)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (carrier_id, carrier_event_id) DO NOTHING
                RETURNING id
                """, Long.class, shipmentId, carrierId, c.carrierEventId(), c.eventCode(),
                Timestamp.from(c.occurredAt()), Timestamp.from(receivedAt), c.location(), c.ndrReason(), source,
                outcome.name(), detail == null || detail.length() <= 300 ? detail : detail.substring(0, 300)).isEmpty();
    }

    public String existingOutcome(long carrierId, String eventId) {
        return jdbc.queryForList("SELECT outcome FROM scan_event WHERE carrier_id = ? AND carrier_event_id = ?",
                String.class, carrierId, eventId).stream().findFirst().orElse(null);
    }

    public List<StoredScan> findByShipment(UUID shipmentId) {
        return jdbc.query("""
                SELECT carrier_event_id, event_code, occurred_at, received_at, location, ndr_reason, source, outcome, detail
                  FROM scan_event WHERE shipment_id = ? ORDER BY occurred_at, id
                """, (rs, i) -> new StoredScan(rs.getString(1), rs.getString(2), rs.getTimestamp(3).toInstant(),
                rs.getTimestamp(4).toInstant(), rs.getString(5), rs.getString(6), rs.getString(7), rs.getString(8),
                rs.getString(9)), shipmentId);
    }
}
