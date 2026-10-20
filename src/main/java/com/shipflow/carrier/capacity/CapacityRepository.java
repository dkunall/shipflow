package com.shipflow.carrier.capacity;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Date;
import java.time.LocalDate;
import java.util.List;

/**
 * Concurrency-safe daily capacity. Reservation is ONE atomic statement: lazily creates the day's row from the
 * zone default or increments it only while {@code reserved < capacity}. Under N concurrent callers PostgreSQL
 * serialises on the row, re-evaluates the WHERE on the latest version, and the CHECK constraint
 * (reserved <= capacity) is a backstop - over-allocation is impossible even with bugs elsewhere.
 */
@Repository
public class CapacityRepository {
    private final JdbcTemplate jdbc;

    public CapacityRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public boolean tryReserve(long carrierId, String zone, LocalDate day) {
        List<Integer> r = jdbc.queryForList("""
                INSERT INTO carrier_capacity (carrier_id, zone_code, service_date, capacity, reserved)
                SELECT cz.carrier_id, cz.zone_code, ?, cz.default_daily_capacity, 1
                  FROM carrier_zone cz
                 WHERE cz.carrier_id = ? AND cz.zone_code = ? AND cz.default_daily_capacity > 0
                ON CONFLICT (carrier_id, zone_code, service_date)
                DO UPDATE SET reserved = carrier_capacity.reserved + 1
                      WHERE carrier_capacity.reserved < carrier_capacity.capacity
                RETURNING reserved
                """, Integer.class, Date.valueOf(day), carrierId, zone);
        if (!r.isEmpty()) {
            return true;
        }
        // default capacity 0 but an explicit ops override row may exist
        return jdbc.update("""
                UPDATE carrier_capacity SET reserved = reserved + 1
                 WHERE carrier_id = ? AND zone_code = ? AND service_date = ? AND reserved < capacity
                   AND NOT EXISTS (SELECT 1 FROM carrier_zone cz WHERE cz.carrier_id = ? AND cz.zone_code = ?
                                     AND cz.default_daily_capacity > 0)
                """, carrierId, zone, Date.valueOf(day), carrierId, zone) == 1;
    }

    public boolean release(long carrierId, String zone, LocalDate day) {
        return jdbc.update("""
                UPDATE carrier_capacity SET reserved = reserved - 1
                 WHERE carrier_id = ? AND zone_code = ? AND service_date = ? AND reserved > 0
                """, carrierId, zone, Date.valueOf(day)) == 1;
    }

    /** Ops override; fails (CHECK) if set below what is already reserved. */
    public void setCapacity(long carrierId, String zone, LocalDate day, int capacity) {
        jdbc.update("""
                INSERT INTO carrier_capacity (carrier_id, zone_code, service_date, capacity, reserved)
                VALUES (?, ?, ?, ?, 0)
                ON CONFLICT (carrier_id, zone_code, service_date) DO UPDATE SET capacity = EXCLUDED.capacity
                """, carrierId, zone, Date.valueOf(day), capacity);
    }

    public record Usage(int capacity, int reserved) {
    }

    public Usage usage(long carrierId, String zone, LocalDate day) {
        List<Usage> u = jdbc.query("""
                SELECT capacity, reserved FROM carrier_capacity WHERE carrier_id = ? AND zone_code = ? AND service_date = ?
                """, (rs, i) -> new Usage(rs.getInt(1), rs.getInt(2)), carrierId, zone, Date.valueOf(day));
        return u.isEmpty() ? new Usage(0, 0) : u.get(0);
    }
}
