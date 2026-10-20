package com.shipflow.edd;

import com.shipflow.stats.StatsDeltas;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.HashMap;
import java.util.List;
import java.util.ArrayList;
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

    public void add(Map<StatsDeltas.LaneKey, Integer> samples) {
        if (samples.isEmpty()) {
            return;
        }
        List<Object[]> args = new ArrayList<>(samples.size());
        samples.forEach((k, n) -> args.add(new Object[]{k.carrierId(), k.originHubId(), k.zone(), k.leadDays(), n}));
        jdbc.batchUpdate("""
                INSERT INTO lane_lead_time (carrier_id, origin_hub_id, zone_code, lead_days, samples)
                VALUES (?, ?, ?, ?, ?)
                ON CONFLICT (carrier_id, origin_hub_id, zone_code, lead_days)
                DO UPDATE SET samples = lane_lead_time.samples + EXCLUDED.samples
                """, args);
    }
}
