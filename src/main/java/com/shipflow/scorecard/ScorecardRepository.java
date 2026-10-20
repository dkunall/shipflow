package com.shipflow.scorecard;

import com.shipflow.stats.StatsDeltas;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Date;
import java.util.Map;
import java.util.ArrayList;
import java.time.LocalDate;
import java.util.List;

/** Incremental (UPSERT +=) daily buckets; the rolling window is a range scan over <= window-days rows. */
@Repository
public class ScorecardRepository {
    private final JdbcTemplate jdbc;

    public ScorecardRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Applies pre-aggregated increments (one UPSERT per carrier/zone/day; keys arrive sorted). */
    public void add(Map<StatsDeltas.ScorecardKey, StatsDeltas.Scorecard> deltas) {
        if (deltas.isEmpty()) {
            return;
        }
        List<Object[]> args = new ArrayList<>(deltas.size());
        deltas.forEach((k, v) -> args.add(new Object[]{k.carrierId(), k.zone(), Date.valueOf(k.day()), v.delivered(),
                v.onTime(), v.firstAttempt(), v.rto()}));
        jdbc.batchUpdate("""
                INSERT INTO carrier_scorecard_daily (carrier_id, zone_code, day, delivered, on_time, first_attempt, rto)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (carrier_id, zone_code, day) DO UPDATE SET
                    delivered = carrier_scorecard_daily.delivered + EXCLUDED.delivered,
                    on_time = carrier_scorecard_daily.on_time + EXCLUDED.on_time,
                    first_attempt = carrier_scorecard_daily.first_attempt + EXCLUDED.first_attempt,
                    rto = carrier_scorecard_daily.rto + EXCLUDED.rto
                """, args);
    }

    public record Totals(long delivered, long onTime, long firstAttempt, long rto) {
    }

    public Totals totals(long carrierId, String zone, LocalDate since) {
        List<Totals> t = jdbc.query("""
                SELECT coalesce(sum(delivered),0), coalesce(sum(on_time),0), coalesce(sum(first_attempt),0), coalesce(sum(rto),0)
                  FROM carrier_scorecard_daily WHERE carrier_id = ? AND zone_code = ? AND day >= ?
                """, (rs, i) -> new Totals(rs.getLong(1), rs.getLong(2), rs.getLong(3), rs.getLong(4)),
                carrierId, zone, Date.valueOf(since));
        return t.get(0);
    }
}
