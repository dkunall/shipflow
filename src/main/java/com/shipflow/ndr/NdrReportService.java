package com.shipflow.ndr;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** NDR analytics over delivery_attempt (partial index on failed attempts by time). */
@Service
public class NdrReportService {
    public record ReasonRow(String reason, long failedAttempts, long rtoDecisions) {
    }

    public record CarrierRow(String carrier, long failedAttempts, long shipmentsAffected, long rtoDecisions) {
    }

    public record Report(Instant from, Instant to, UUID merchantId, long failedAttempts, long shipmentsAffected,
                         long rtoDecisions, long reattemptDecisions, long openNdrShipments,
                         List<ReasonRow> byReason, List<CarrierRow> byCarrier) {
    }

    private final NamedParameterJdbcTemplate jdbc;

    public NdrReportService(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Report report(Instant from, Instant to, UUID merchantId) {
        MapSqlParameterSource p = new MapSqlParameterSource("from", Timestamp.from(from)).addValue("to", Timestamp.from(to))
                .addValue("m", merchantId);
        String scope = merchantId == null ? "" : " AND s.merchant_id = :m";
        String base = " FROM delivery_attempt a JOIN shipment s ON s.id = a.shipment_id"
                + " WHERE a.outcome = 'FAILED' AND a.occurred_at >= :from AND a.occurred_at < :to" + scope;
        var totals = jdbc.queryForMap("SELECT count(*) AS n, count(DISTINCT a.shipment_id) AS ships,"
                + " count(*) FILTER (WHERE a.action = 'RTO') AS rto, count(*) FILTER (WHERE a.action = 'REATTEMPT') AS re"
                + base, p);
        List<ReasonRow> byReason = jdbc.query("SELECT a.ndr_reason, count(*), count(*) FILTER (WHERE a.action = 'RTO')"
                + base + " GROUP BY a.ndr_reason ORDER BY count(*) DESC, a.ndr_reason", p,
                (rs, i) -> new ReasonRow(rs.getString(1), rs.getLong(2), rs.getLong(3)));
        List<CarrierRow> byCarrier = jdbc.query("SELECT c.code, count(*), count(DISTINCT a.shipment_id),"
                + " count(*) FILTER (WHERE a.action = 'RTO')" + base.replace(" WHERE", " JOIN carrier c ON c.id = a.carrier_id WHERE")
                + " GROUP BY c.code ORDER BY count(*) DESC, c.code", p,
                (rs, i) -> new CarrierRow(rs.getString(1), rs.getLong(2), rs.getLong(3), rs.getLong(4)));
        Long open = jdbc.queryForObject("SELECT count(*) FROM shipment s WHERE s.status = 'DELIVERY_FAILED'" + scope, p,
                Long.class);
        return new Report(from, to, merchantId, ((Number) totals.get("n")).longValue(),
                ((Number) totals.get("ships")).longValue(), ((Number) totals.get("rto")).longValue(),
                ((Number) totals.get("re")).longValue(), open == null ? 0 : open, byReason, byCarrier);
    }
}
