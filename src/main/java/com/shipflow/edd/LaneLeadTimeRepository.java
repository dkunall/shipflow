package com.shipflow.edd;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.HashMap;
import java.util.Map;

@Repository
public class LaneLeadTimeRepository {
    private final JdbcTemplate jdbc;

    public LaneLeadTimeRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public LeadTimeHistogram histogram(long carrierId, long originHubId, String zone) {
        Map<Integer, Long> buckets = new HashMap<>();
        jdbc.query("SELECT lead_days, samples FROM lane_lead_time WHERE carrier_id = ? AND origin_hub_id = ? AND zone_code = ?",
                rs -> {
                    buckets.put(rs.getInt(1), rs.getLong(2));
                }, carrierId, originHubId, zone);
        return LeadTimeHistogram.of(buckets);
    }

    public void record(long carrierId, long originHubId, String zone, int leadDays) {
        jdbc.update("""
                INSERT INTO lane_lead_time (carrier_id, origin_hub_id, zone_code, lead_days, samples)
                VALUES (?, ?, ?, ?, 1)
                ON CONFLICT (carrier_id, origin_hub_id, zone_code, lead_days)
                DO UPDATE SET samples = lane_lead_time.samples + 1
                """, carrierId, originHubId, zone, leadDays);
    }
}
