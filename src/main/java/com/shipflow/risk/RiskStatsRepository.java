package com.shipflow.risk;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class RiskStatsRepository {
    private final JdbcTemplate jdbc;

    public RiskStatsRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public RiskInput.DeliveryStats pincode(String pincode) {
        return jdbc.query("SELECT attempts, failures FROM pincode_delivery_stats WHERE pincode = ?",
                (rs, i) -> new RiskInput.DeliveryStats(rs.getLong(1), rs.getLong(2)), pincode)
                .stream().findFirst().orElse(RiskInput.DeliveryStats.NONE);
    }

    public RiskInput.CustomerStats customer(String phoneHash) {
        return jdbc.query("SELECT attempts, failures, rto FROM customer_delivery_stats WHERE phone_hash = ?",
                (rs, i) -> new RiskInput.CustomerStats(rs.getLong(1), rs.getLong(2), rs.getLong(3)), phoneHash)
                .stream().findFirst().orElse(RiskInput.CustomerStats.NONE);
    }

    public void recordAttempt(String pincode, String phoneHash, boolean failed) {
        int f = failed ? 1 : 0;
        jdbc.update("""
                INSERT INTO pincode_delivery_stats (pincode, attempts, failures) VALUES (?, 1, ?)
                ON CONFLICT (pincode) DO UPDATE SET attempts = pincode_delivery_stats.attempts + 1,
                                                    failures = pincode_delivery_stats.failures + EXCLUDED.failures
                """, pincode, f);
        jdbc.update("""
                INSERT INTO customer_delivery_stats (phone_hash, attempts, failures, rto) VALUES (?, 1, ?, 0)
                ON CONFLICT (phone_hash) DO UPDATE SET attempts = customer_delivery_stats.attempts + 1,
                                                       failures = customer_delivery_stats.failures + EXCLUDED.failures
                """, phoneHash, f);
    }

    public void recordRto(String phoneHash) {
        jdbc.update("""
                INSERT INTO customer_delivery_stats (phone_hash, attempts, failures, rto) VALUES (?, 0, 0, 1)
                ON CONFLICT (phone_hash) DO UPDATE SET rto = customer_delivery_stats.rto + 1
                """, phoneHash);
    }
}
