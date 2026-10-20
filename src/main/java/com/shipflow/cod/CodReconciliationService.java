package com.shipflow.cod;

import com.shipflow.carrier.CarrierDirectory;
import com.shipflow.common.error.ErrorCode;
import com.shipflow.common.error.ShipFlowException;
import com.shipflow.observability.ShipFlowMetrics;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * COD cash reconciliation: expected = COD shipments the carrier DELIVERED in the period; remitted = sum of the
 * carrier's remittance lines for those tracking numbers (+ lines received in the period for unknown shipments).
 * Outcome per (tracking number, currency): MATCHED / SHORT / EXCESS / MISSING, exact numeric(12,2) arithmetic in
 * SQL. A re-run of the same (carrier, period) atomically replaces the result set -> idempotent.
 */
@Service
public class CodReconciliationService {
    public record ResultRow(String trackingNumber, String currency, UUID shipmentId, BigDecimal expected,
                            BigDecimal remitted, BigDecimal difference, String outcome) {
    }

    public record RunSummary(long runId, String carrier, Instant periodFrom, Instant periodTo, int runCount,
                             int matched, int shortCount, int excess, int missing, BigDecimal expectedTotal,
                             BigDecimal remittedTotal, Instant lastRunAt) {
    }

    private final JdbcTemplate jdbc;
    private final CarrierDirectory carriers;
    private final ShipFlowMetrics metrics;
    private final Clock clock;

    public CodReconciliationService(JdbcTemplate jdbc, CarrierDirectory carriers, ShipFlowMetrics metrics, Clock clock) {
        this.jdbc = jdbc;
        this.carriers = carriers;
        this.metrics = metrics;
        this.clock = clock;
    }

    @Transactional
    public long recordRemittance(RemittanceRequest r) {
        var carrier = carriers.requireCode(r.carrierCode());
        Set<String> seen = new HashSet<>();
        BigDecimal total = BigDecimal.ZERO;
        for (var l : r.lines()) {
            if (!seen.add(l.trackingNumber())) {
                throw new ShipFlowException(ErrorCode.VALIDATION_FAILED, "duplicate line for " + l.trackingNumber());
            }
            total = total.add(l.amount());
        }
        long id;
        try {
            id = jdbc.queryForObject("""
                    INSERT INTO cod_remittance (carrier_id, remittance_ref, currency, total_amount, received_at)
                    VALUES (?, ?, ?, ?, ?) RETURNING id
                    """, Long.class, carrier.id(), r.remittanceRef(), r.currency(), total, Timestamp.from(r.receivedAt()));
        } catch (DuplicateKeyException e) {
            throw new ShipFlowException(ErrorCode.DUPLICATE_REMITTANCE,
                    "remittance " + r.remittanceRef() + " already recorded for " + r.carrierCode());
        }
        jdbc.batchUpdate("INSERT INTO cod_remittance_line (remittance_id, tracking_number, amount) VALUES (?, ?, ?)",
                r.lines(), 500, (ps, l) -> {
                    ps.setLong(1, id);
                    ps.setString(2, l.trackingNumber());
                    ps.setBigDecimal(3, l.amount());
                });
        return id;
    }

    @Transactional
    public RunSummary reconcile(String carrierCode, Instant from, Instant to) {
        if (!to.isAfter(from)) {
            throw new ShipFlowException(ErrorCode.VALIDATION_FAILED, "period 'to' must be after 'from'");
        }
        var carrier = carriers.requireCode(carrierCode);
        Instant now = clock.instant();
        // upsert the run row; its row lock serialises concurrent re-runs of the same period
        long runId = jdbc.queryForObject("""
                INSERT INTO cod_reconciliation_run (carrier_id, period_from, period_to, run_count, last_run_at)
                VALUES (?, ?, ?, 1, ?)
                ON CONFLICT (carrier_id, period_from, period_to)
                DO UPDATE SET run_count = cod_reconciliation_run.run_count + 1, last_run_at = EXCLUDED.last_run_at
                RETURNING id
                """, Long.class, carrier.id(), Timestamp.from(from), Timestamp.from(to), Timestamp.from(now));
        jdbc.update("DELETE FROM cod_reconciliation_result WHERE run_id = ?", runId);
        Object[] w = {carrier.id(), Timestamp.from(from), Timestamp.from(to)};
        jdbc.update("""
                WITH expected AS (
                    SELECT s.id, s.tracking_number, s.currency, s.cod_amount
                      FROM shipment s
                     WHERE s.carrier_id = ? AND s.payment_mode = 'COD' AND s.status = 'DELIVERED'
                       AND s.delivered_at >= ? AND s.delivered_at < ?),
                window_args AS (SELECT ?::bigint AS carrier_id, ?::timestamptz AS f, ?::timestamptz AS t),
                remitted AS (
                    SELECT l.tracking_number, r.currency, sum(l.amount) AS amount
                      FROM cod_remittance_line l
                      JOIN cod_remittance r ON r.id = l.remittance_id
                      JOIN window_args wa ON r.carrier_id = wa.carrier_id
                     WHERE l.tracking_number IN (SELECT tracking_number FROM expected)
                        OR (r.received_at >= wa.f AND r.received_at < wa.t)
                     GROUP BY l.tracking_number, r.currency)
                INSERT INTO cod_reconciliation_result (run_id, tracking_number, currency, shipment_id, expected, remitted,
                                                       difference, outcome)
                SELECT ?, coalesce(e.tracking_number, m.tracking_number), coalesce(e.currency, m.currency), e.id,
                       coalesce(e.cod_amount, 0), coalesce(m.amount, 0), coalesce(m.amount, 0) - coalesce(e.cod_amount, 0),
                       CASE WHEN e.id IS NULL THEN 'EXCESS'
                            WHEN m.amount IS NULL THEN 'MISSING'
                            WHEN m.amount = e.cod_amount THEN 'MATCHED'
                            WHEN m.amount < e.cod_amount THEN 'SHORT'
                            ELSE 'EXCESS' END
                  FROM expected e
                  FULL OUTER JOIN remitted m ON m.tracking_number = e.tracking_number AND m.currency = e.currency
                 WHERE e.id IS NOT NULL
                    OR NOT EXISTS (SELECT 1 FROM shipment s2, window_args wa
                                    WHERE s2.tracking_number = m.tracking_number AND s2.carrier_id = wa.carrier_id
                                      AND s2.payment_mode = 'COD' AND s2.status = 'DELIVERED' AND s2.currency = m.currency
                                      AND NOT (s2.delivered_at >= wa.f AND s2.delivered_at < wa.t))
                """, w[0], w[1], w[2], w[0], w[1], w[2], runId);
        jdbc.update("""
                UPDATE cod_reconciliation_run r SET
                    matched = x.matched, short = x.short, excess = x.excess, missing = x.missing,
                    expected_total = x.expected_total, remitted_total = x.remitted_total
                  FROM (SELECT count(*) FILTER (WHERE outcome = 'MATCHED') AS matched,
                               count(*) FILTER (WHERE outcome = 'SHORT') AS short,
                               count(*) FILTER (WHERE outcome = 'EXCESS') AS excess,
                               count(*) FILTER (WHERE outcome = 'MISSING') AS missing,
                               coalesce(sum(expected), 0) AS expected_total, coalesce(sum(remitted), 0) AS remitted_total
                          FROM cod_reconciliation_result WHERE run_id = ?) x
                 WHERE r.id = ?
                """, runId, runId);
        RunSummary s = summary(runId);
        metrics.codReconciliation("MATCHED", s.matched());
        metrics.codReconciliation("SHORT", s.shortCount());
        metrics.codReconciliation("EXCESS", s.excess());
        metrics.codReconciliation("MISSING", s.missing());
        return s;
    }

    public RunSummary summary(long runId) {
        return jdbc.query("""
                SELECT r.id, c.code, r.period_from, r.period_to, r.run_count, r.matched, r.short, r.excess, r.missing,
                       r.expected_total, r.remitted_total, r.last_run_at
                  FROM cod_reconciliation_run r JOIN carrier c ON c.id = r.carrier_id WHERE r.id = ?
                """, (rs, i) -> new RunSummary(rs.getLong(1), rs.getString(2), rs.getTimestamp(3).toInstant(),
                rs.getTimestamp(4).toInstant(), rs.getInt(5), rs.getInt(6), rs.getInt(7), rs.getInt(8), rs.getInt(9),
                rs.getBigDecimal(10), rs.getBigDecimal(11), rs.getTimestamp(12).toInstant()), runId)
                .stream().findFirst().orElseThrow(() -> ShipFlowException.notFound("reconciliation run", runId));
    }

    public List<ResultRow> results(long runId) {
        return jdbc.query("""
                SELECT tracking_number, currency, shipment_id, expected, remitted, difference, outcome
                  FROM cod_reconciliation_result WHERE run_id = ? ORDER BY outcome, tracking_number
                """, (rs, i) -> new ResultRow(rs.getString(1), rs.getString(2), rs.getObject(3, UUID.class),
                rs.getBigDecimal(4), rs.getBigDecimal(5), rs.getBigDecimal(6), rs.getString(7)), runId);
    }

    public List<Map<String, Object>> activeCarrierCodes() {
        return jdbc.queryForList("SELECT code FROM carrier WHERE active ORDER BY code");
    }
}
