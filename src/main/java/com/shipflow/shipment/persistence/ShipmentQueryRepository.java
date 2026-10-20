package com.shipflow.shipment.persistence;

import com.shipflow.shipment.api.ShipmentSummary;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Read model for lists. One query (shipment LEFT JOIN carrier) + one count, both index-backed
 * (ix_shipment_merchant_created / ix_shipment_merchant_status_created).
 */
@Repository
public class ShipmentQueryRepository {
    public record Filter(UUID merchantId, String status, String carrierCode, Instant createdFrom, Instant createdTo) {
    }

    private final NamedParameterJdbcTemplate jdbc;

    public ShipmentQueryRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<ShipmentSummary> list(Filter f, int page, int size) {
        MapSqlParameterSource p = params(f).addValue("limit", size).addValue("offset", (long) page * size);
        return jdbc.query("""
                SELECT s.id, s.tracking_number, s.merchant_order_ref, s.status, c.code AS carrier_code, s.carrier_awb,
                       s.promised_date, s.payment_mode, s.cod_amount, s.currency, s.risk_band, s.attempt_count, s.created_at
                  FROM shipment s LEFT JOIN carrier c ON c.id = s.carrier_id
                """ + where(f) + " ORDER BY s.created_at DESC, s.id LIMIT :limit OFFSET :offset", p,
                (rs, i) -> new ShipmentSummary(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3),
                        rs.getString(4), rs.getString(5), rs.getString(6),
                        rs.getDate(7) == null ? null : rs.getDate(7).toLocalDate(), rs.getString(8),
                        rs.getBigDecimal(9), rs.getString(10), rs.getString(11), rs.getInt(12),
                        rs.getTimestamp(13).toInstant()));
    }

    public long count(Filter f) {
        Long n = jdbc.queryForObject("SELECT count(*) FROM shipment s LEFT JOIN carrier c ON c.id = s.carrier_id "
                + where(f), params(f), Long.class);
        return n == null ? 0 : n;
    }

    private static String where(Filter f) {
        StringBuilder w = new StringBuilder(" WHERE 1=1");
        if (f.merchantId() != null) {
            w.append(" AND s.merchant_id = :merchant");
        }
        if (f.status() != null) {
            w.append(" AND s.status = :status");
        }
        if (f.carrierCode() != null) {
            w.append(" AND c.code = :carrier");
        }
        if (f.createdFrom() != null) {
            w.append(" AND s.created_at >= :from");
        }
        if (f.createdTo() != null) {
            w.append(" AND s.created_at < :to");
        }
        return w.toString();
    }

    private static MapSqlParameterSource params(Filter f) {
        return new MapSqlParameterSource()
                .addValue("merchant", f.merchantId())
                .addValue("status", f.status())
                .addValue("carrier", f.carrierCode())
                .addValue("from", f.createdFrom() == null ? null : Timestamp.from(f.createdFrom()))
                .addValue("to", f.createdTo() == null ? null : Timestamp.from(f.createdTo()));
    }
}
