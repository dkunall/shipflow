package com.shipflow.risk;

import com.shipflow.stats.StatsDeltas;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.Map;

import java.util.List;

import java.util.ArrayList;

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

    public void addPincodes(Map<String, StatsDeltas.Attempts> deltas) {
        if (deltas.isEmpty()) {
            return;
        }
        List<Object[]> args = new ArrayList<>(deltas.size());
        deltas.forEach((pin, d) -> args.add(new Object[]{pin, d.attempts(), d.failures()}));
        jdbc.batchUpdate("""
                INSERT INTO pincode_delivery_stats (pincode, attempts, failures) VALUES (?, ?, ?)
                ON CONFLICT (pincode) DO UPDATE SET attempts = pincode_delivery_stats.attempts + EXCLUDED.attempts,
                                                    failures = pincode_delivery_stats.failures + EXCLUDED.failures
                """, args);
    }

    public void addCustomers(Map<String, StatsDeltas.Customer> deltas) {
        if (deltas.isEmpty()) {
            return;
        }
        List<Object[]> args = new ArrayList<>(deltas.size());
        deltas.forEach((phone, d) -> args.add(new Object[]{phone, d.attempts(), d.failures(), d.rto()}));
        jdbc.batchUpdate("""
                INSERT INTO customer_delivery_stats (phone_hash, attempts, failures, rto) VALUES (?, ?, ?, ?)
                ON CONFLICT (phone_hash) DO UPDATE SET attempts = customer_delivery_stats.attempts + EXCLUDED.attempts,
                                                       failures = customer_delivery_stats.failures + EXCLUDED.failures,
                                                       rto = customer_delivery_stats.rto + EXCLUDED.rto
                """, args);
    }
}
