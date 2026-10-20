package com.shipflow.allocation.application;

import com.shipflow.allocation.domain.CarrierCandidate;
import com.shipflow.carrier.CarrierDirectory;
import com.shipflow.carrier.domain.RateCard;
import com.shipflow.carrier.domain.RateSlab;
import com.shipflow.config.ShipFlowProperties;
import com.shipflow.scorecard.ScorecardMath;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.Array;
import java.sql.Date;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * Loads every candidate for a zone in ONE query: serviceability + commercial terms + rate slabs (array_agg)
 * + the day's capacity snapshot + rolling scorecard aggregate. No N+1 per carrier.
 */
@Repository
public class CandidateRepository {
    private final JdbcTemplate jdbc;
    private final CarrierDirectory carriers;
    private final ShipFlowProperties.Scorecard scorecardProps;

    public CandidateRepository(JdbcTemplate jdbc, CarrierDirectory carriers, ShipFlowProperties props) {
        this.jdbc = jdbc;
        this.carriers = carriers;
        this.scorecardProps = props.scorecard();
    }

    public List<CarrierCandidate> findCandidates(String zone, LocalDate serviceDate, LocalDate scorecardSince) {
        return jdbc.query("""
                SELECT cz.carrier_id, cz.sla_days, cz.cod_supported, cz.max_weight_g, cz.currency, cz.extra_per_500g,
                       cz.cod_fee_percent, cz.cod_fee_min,
                       coalesce(cc.capacity, cz.default_daily_capacity) AS capacity, coalesce(cc.reserved, 0) AS reserved,
                       coalesce(sc.delivered, 0), coalesce(sc.on_time, 0), coalesce(sc.first_attempt, 0), coalesce(sc.rto, 0),
                       sl.ups, sl.prices
                  FROM carrier_zone cz
                  JOIN carrier c ON c.id = cz.carrier_id AND c.active
                  LEFT JOIN carrier_capacity cc
                         ON cc.carrier_id = cz.carrier_id AND cc.zone_code = cz.zone_code AND cc.service_date = ?
                  LEFT JOIN LATERAL (
                        SELECT sum(d.delivered) AS delivered, sum(d.on_time) AS on_time,
                               sum(d.first_attempt) AS first_attempt, sum(d.rto) AS rto
                          FROM carrier_scorecard_daily d
                         WHERE d.carrier_id = cz.carrier_id AND d.zone_code = cz.zone_code AND d.day >= ?) sc ON true
                  LEFT JOIN LATERAL (
                        SELECT array_agg(rs.up_to_g ORDER BY rs.up_to_g) AS ups, array_agg(rs.price ORDER BY rs.up_to_g) AS prices
                          FROM rate_slab rs WHERE rs.carrier_id = cz.carrier_id AND rs.zone_code = cz.zone_code) sl ON true
                 WHERE cz.zone_code = ?
                 ORDER BY c.code
                """, (rs, i) -> {
            List<RateSlab> slabs = new ArrayList<>();
            Array ups = rs.getArray("ups");
            Array prices = rs.getArray("prices");
            if (ups != null && prices != null) {
                Integer[] u = (Integer[]) ups.getArray();
                BigDecimal[] p = (BigDecimal[]) prices.getArray();
                for (int k = 0; k < u.length; k++) {
                    slabs.add(new RateSlab(u[k], p[k]));
                }
            }
            String currency = rs.getString("currency");
            RateCard card = new RateCard(currency, slabs, rs.getBigDecimal("extra_per_500g"), rs.getInt("max_weight_g"),
                    rs.getBigDecimal("cod_fee_percent"), rs.getBigDecimal("cod_fee_min"));
            var scorecard = ScorecardMath.scorecard(rs.getLong(11), rs.getLong(12), rs.getLong(13), rs.getLong(14),
                    scorecardProps);
            return new CarrierCandidate(carriers.require(rs.getLong("carrier_id")), rs.getInt("sla_days"),
                    rs.getBoolean("cod_supported"), card, rs.getInt("capacity"), rs.getInt("reserved"), scorecard);
        }, Date.valueOf(serviceDate), Date.valueOf(scorecardSince), zone);
    }
}
