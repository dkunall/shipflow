package com.shipflow.stats;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Date;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;

@Repository
public class OutcomeLogRepository {
    private final JdbcTemplate jdbc;

    public OutcomeLogRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void append(UUID shipmentId, DeliveryOutcome o) {
        jdbc.update("""
                INSERT INTO delivery_outcome (shipment_id, outcome, carrier_id, origin_hub_id, zone_code, local_day,
                                              lead_days, on_time, first_attempt, pincode, phone_hash)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, shipmentId, o.kind().name(), o.carrierId(), o.originHubId(), o.zoneCode(), Date.valueOf(o.localDay()),
                o.leadDays(), o.onTime(), o.firstAttempt(), o.pincode(), o.phoneHash());
    }

    /**
     * Claims (and removes) up to {@code limit} oldest outcomes. Must run in the caller's transaction together with
     * applying the deltas: a rollback restores the rows, concurrent aggregators skip each other's rows.
     */
    public List<DeliveryOutcome> claim(int limit) {
        return jdbc.query("""
                DELETE FROM delivery_outcome WHERE id IN (
                    SELECT id FROM delivery_outcome ORDER BY id LIMIT ? FOR UPDATE SKIP LOCKED)
                RETURNING outcome, carrier_id, origin_hub_id, zone_code, local_day, lead_days, on_time, first_attempt,
                          pincode, phone_hash
                """, OutcomeLogRepository::map, limit);
    }

    public long backlog() {
        Long n = jdbc.queryForObject("SELECT count(*) FROM delivery_outcome", Long.class);
        return n == null ? 0 : n;
    }

    private static DeliveryOutcome map(ResultSet rs, int i) throws SQLException {
        return new DeliveryOutcome(DeliveryOutcome.Kind.valueOf(rs.getString(1)), (Long) rs.getObject(2, Long.class),
                (Long) rs.getObject(3, Long.class), rs.getString(4), rs.getDate(5).toLocalDate(),
                (Integer) rs.getObject(6, Integer.class), rs.getBoolean(7), rs.getBoolean(8), rs.getString(9),
                rs.getString(10));
    }
}
