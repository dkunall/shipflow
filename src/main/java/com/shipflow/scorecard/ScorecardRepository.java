package com.shipflow.scorecard;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Date;
import java.time.LocalDate;
import java.util.List;

/** Incremental (UPSERT +=) daily buckets; the rolling window is a range scan over <= window-days rows. */
@Repository
public class ScorecardRepository {
    private final JdbcTemplate jdbc;

    public ScorecardRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void recordDelivered(long carrierId, String zone, LocalDate day, boolean onTime, boolean firstAttempt) {
        jdbc.update("""
                INSERT INTO carrier_scorecard_daily (carrier_id, zone_code, day, delivered, on_time, first_attempt, rto)
                VALUES (?, ?, ?, 1, ?, ?, 0)
                ON CONFLICT (carrier_id, zone_code, day) DO UPDATE SET
                    delivered = carrier_scorecard_daily.delivered + 1,
                    on_time = carrier_scorecard_daily.on_time + EXCLUDED.on_time,
                    first_attempt = carrier_scorecard_daily.first_attempt + EXCLUDED.first_attempt
                """, carrierId, zone, Date.valueOf(day), onTime ? 1 : 0, firstAttempt ? 1 : 0);
    }

    public void recordRto(long carrierId, String zone, LocalDate day) {
        jdbc.update("""
                INSERT INTO carrier_scorecard_daily (carrier_id, zone_code, day, delivered, on_time, first_attempt, rto)
                VALUES (?, ?, ?, 0, 0, 0, 1)
                ON CONFLICT (carrier_id, zone_code, day) DO UPDATE SET rto = carrier_scorecard_daily.rto + 1
                """, carrierId, zone, Date.valueOf(day));
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
