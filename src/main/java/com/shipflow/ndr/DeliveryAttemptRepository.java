package com.shipflow.ndr;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

@Repository
public class DeliveryAttemptRepository {
    private final JdbcTemplate jdbc;

    public DeliveryAttemptRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void record(UUID shipmentId, int attemptNo, String outcome, String reason, String action, long carrierId,
                       Instant at) {
        jdbc.update("""
                INSERT INTO delivery_attempt (shipment_id, attempt_no, outcome, ndr_reason, action, carrier_id, occurred_at)
                VALUES (?, ?, ?, ?, ?, ?, ?) ON CONFLICT (shipment_id, attempt_no) DO NOTHING
                """, shipmentId, attemptNo, outcome, reason, action, carrierId, Timestamp.from(at));
    }
}
