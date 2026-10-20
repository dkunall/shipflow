package com.shipflow.cod;

import com.shipflow.config.ShipFlowProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;

/** Nightly: reconcile yesterday (business zone) for every active carrier. Safe to re-run (idempotent). */
@Component
public class CodReconciliationJob {
    private static final Logger log = LoggerFactory.getLogger(CodReconciliationJob.class);
    private final CodReconciliationService service;
    private final Clock clock;
    private final ZoneId zone;

    public CodReconciliationJob(CodReconciliationService service, Clock clock, ShipFlowProperties props) {
        this.service = service;
        this.clock = clock;
        this.zone = ZoneId.of(props.cod().reconcileZone());
    }

    @Scheduled(cron = "${shipflow.cod.reconcile-cron}", zone = "${shipflow.cod.reconcile-zone}")
    public void nightly() {
        LocalDate yesterday = LocalDate.now(clock.withZone(zone)).minusDays(1);
        Instant from = yesterday.atStartOfDay(zone).toInstant();
        Instant to = yesterday.plusDays(1).atStartOfDay(zone).toInstant();
        for (var row : service.activeCarrierCodes()) {
            String code = (String) row.get("code");
            try {
                var s = service.reconcile(code, from, to);
                log.info("COD reconciliation carrier={} matched={} short={} excess={} missing={}", code, s.matched(),
                        s.shortCount(), s.excess(), s.missing());
            } catch (RuntimeException e) {
                log.warn("COD reconciliation failed carrier={} error={}", code, e.toString());
            }
        }
    }
}
