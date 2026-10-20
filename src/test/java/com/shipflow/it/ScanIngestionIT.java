package com.shipflow.it;

import com.fasterxml.jackson.databind.JsonNode;
import com.shipflow.security.HmacSigner;
import com.shipflow.support.AbstractPostgresIT;
import com.shipflow.support.TestJwt;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class ScanIngestionIT extends AbstractPostgresIT {
    private final Instant t0 = Instant.now().minus(5, ChronoUnit.HOURS);
    private String id;
    private String trk;

    @BeforeEach
    void shipment() throws Exception {
        thirdParty("FAST", "12.00", 2, 100);
        JsonNode s = bookOk(m1, "ORD-SCAN", "COD", "120.00");
        id = s.get("id").asText();
        trk = s.get("trackingNumber").asText();
    }

    private Instant t(int minutes) {
        return t0.plus(minutes, ChronoUnit.MINUTES);
    }

    @Test
    void jumpScanInfersIntermediateStepsThenDelivers() throws Exception {
        JsonNode r = scanOk("FAST", "e1", trk, "OUT_FOR_DELIVERY", t(10), null);
        assertThat(r.get("outcome").asText()).isEqualTo("APPLIED");
        assertThat(r.get("appliedSteps").toString()).contains("PICKED_UP", "OUT_FOR_DELIVERY");
        assertThat(count("SELECT count(*) FROM shipment_status_history WHERE shipment_id = ?::uuid AND inferred", id))
                .isGreaterThanOrEqualTo(1);
        scanOk("FAST", "e2", trk, "DELIVERED", t(20), null);
        assertThat(statusOf(id)).isEqualTo("DELIVERED");
        assertThat(count("SELECT count(*) FROM delivery_attempt WHERE shipment_id = ?::uuid AND outcome = 'DELIVERED'", id))
                .isEqualTo(1);
        assertThat(count("SELECT count(*) FROM delivery_outcome WHERE outcome = 'DELIVERED'")).isEqualTo(1);
        flushStats();
        assertThat(count("SELECT count(*) FROM delivery_outcome")).isZero();
        assertThat(count("SELECT coalesce(sum(delivered),0) FROM carrier_scorecard_daily")).isEqualTo(1);
        // terminal: nothing moves it any more
        JsonNode late = scanOk("FAST", "e3", trk, "IN_TRANSIT", t(30), null);
        assertThat(late.get("outcome").asText()).isIn("STALE", "REJECTED");
        assertThat(statusOf(id)).isEqualTo("DELIVERED");
    }

    @Test
    void duplicateEventIdHasNoEffect() throws Exception {
        scanOk("FAST", "dup-1", trk, "PICKED_UP", t(1), null);
        int history = count("SELECT count(*) FROM shipment_status_history WHERE shipment_id = ?::uuid", id);
        JsonNode r = scanOk("FAST", "dup-1", trk, "IN_TRANSIT", t(2), null);
        assertThat(r.get("outcome").asText()).isEqualTo("DUPLICATE");
        assertThat(statusOf(id)).isEqualTo("PICKED_UP");
        assertThat(count("SELECT count(*) FROM shipment_status_history WHERE shipment_id = ?::uuid", id)).isEqualTo(history);
        assertThat(count("SELECT count(*) FROM scan_event")).isEqualTo(1);
    }

    @Test
    void lateEventsAreRecordedButNeverRegressStatus() throws Exception {
        scanOk("FAST", "a", trk, "OUT_FOR_DELIVERY", t(30), null);
        JsonNode late = scanOk("FAST", "b", trk, "IN_TRANSIT", t(10), null);
        assertThat(late.get("outcome").asText()).isEqualTo("OUT_OF_ORDER");
        JsonNode lateHub = scanOk("FAST", "c", trk, "AT_HUB", t(20), null);
        assertThat(lateHub.get("outcome").asText()).isEqualTo("OUT_OF_ORDER");
        assertThat(statusOf(id)).isEqualTo("OUT_FOR_DELIVERY");
        // a newer but lower-rank event is stale
        JsonNode stale = scanOk("FAST", "d", trk, "PICKED_UP", t(40), null);
        assertThat(stale.get("outcome").asText()).isEqualTo("STALE");
        assertThat(statusOf(id)).isEqualTo("OUT_FOR_DELIVERY");
        assertThat(count("SELECT count(*) FROM scan_event WHERE shipment_id = ?::uuid", id)).isEqualTo(4);
        Map<String, Long> outcomes = jdbc.queryForList("SELECT outcome FROM scan_event", String.class).stream()
                .collect(Collectors.groupingBy(o -> o, Collectors.counting()));
        assertThat(outcomes).containsEntry("APPLIED", 1L).containsEntry("OUT_OF_ORDER", 2L).containsEntry("STALE", 1L);
    }

    @Test
    void wrongCarrierAndIllegalMovesAreRejectedButAudited() throws Exception {
        thirdParty("OTHER", "30.00", 3, 10);
        JsonNode r = scanOk("OTHER", "x1", trk, "PICKED_UP", t(1), null);
        assertThat(r.get("outcome").asText()).isEqualTo("REJECTED");
        assertThat(statusOf(id)).isEqualTo("ALLOCATED");
        // RTO_DELIVERED is not reachable from ALLOCATED without an RTO decision
        JsonNode illegal = scanOk("FAST", "x2", trk, "RTO_DELIVERED", t(2), null);
        assertThat(illegal.get("outcome").asText()).isEqualTo("REJECTED");
        assertThat(count("SELECT count(*) FROM scan_event WHERE outcome = 'REJECTED'")).isEqualTo(2);
    }

    @Test
    void validationOfScans() throws Exception {
        scan(TestJwt.ops(), "FAST", "v1", trk, "TELEPORTED", t(1), null).andExpect(status().isBadRequest());
        scan(TestJwt.ops(), "FAST", "v2", trk, "DELIVERY_FAILED", t(1), null).andExpect(status().isBadRequest());
        scan(TestJwt.ops(), "FAST", "v3", trk, "PICKED_UP", Instant.now().plus(2, ChronoUnit.HOURS), null)
                .andExpect(status().isBadRequest());
        scan(TestJwt.ops(), "FAST", "v4", "SF000000000000", "PICKED_UP", t(1), null).andExpect(status().isNotFound());
        scan(TestJwt.merchant(m1), "FAST", "v5", trk, "PICKED_UP", t(1), null).andExpect(status().isForbidden());
        scan(TestJwt.rider(), "FAST", "v6", trk, "PICKED_UP", t(1), null).andExpect(status().isForbidden());
    }

    @Test
    void ndrReattemptsThenAutoRtoAfterMaxAttempts() throws Exception {
        int m = 0;
        for (int attempt = 1; attempt <= 3; attempt++) {
            scanOk("FAST", "ofd-" + attempt, trk, "OUT_FOR_DELIVERY", t(m += 10), null);
            JsonNode r = scanOk("FAST", "df-" + attempt, trk, "DELIVERY_FAILED", t(m += 10), "CUSTOMER_UNAVAILABLE");
            assertThat(r.get("outcome").asText()).isEqualTo("APPLIED");
        }
        assertThat(statusOf(id)).isEqualTo("RTO_INITIATED");
        assertThat(count("SELECT attempt_count FROM shipment WHERE id = ?::uuid", id)).isEqualTo(3);
        assertThat(count("SELECT count(*) FROM delivery_attempt WHERE shipment_id = ?::uuid AND outcome = 'FAILED'", id))
                .isEqualTo(3);
        // a reattempt scan after the RTO decision cannot pull it back (lower rank => STALE)
        assertThat(scanOk("FAST", "ofd-4", trk, "OUT_FOR_DELIVERY", t(m += 10), null).get("outcome").asText())
                .isEqualTo("STALE");
        assertThat(statusOf(id)).isEqualTo("RTO_INITIATED");
        scanOk("FAST", "rto-1", trk, "RTO_IN_TRANSIT", t(m += 10), null);
        scanOk("FAST", "rto-2", trk, "RTO_DELIVERED", t(m += 10), null);
        assertThat(statusOf(id)).isEqualTo("RTO_DELIVERED");
        flushStats();
        assertThat(count("SELECT coalesce(sum(rto),0) FROM carrier_scorecard_daily")).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM outbox_event WHERE event_type = 'shipment.ndr'")).isZero(); // no webhook url

        JsonNode report = body(mvc.perform(get("/api/v1/ndr/report").param("from", t0.minus(1, ChronoUnit.DAYS).toString())
                .param("to", Instant.now().plus(1, ChronoUnit.HOURS).toString()).header("Authorization", TestJwt.ops()))
                .andExpect(status().isOk()));
        assertThat(report.get("failedAttempts").asInt()).isEqualTo(3);
        assertThat(report.get("shipmentsAffected").asInt()).isEqualTo(1);
        assertThat(report.get("rtoDecisions").asInt()).isEqualTo(1);
        assertThat(report.get("byReason").get(0).get("reason").asText()).isEqualTo("CUSTOMER_UNAVAILABLE");
        assertThat(report.get("byCarrier").get(0).get("carrier").asText()).isEqualTo("FAST");
        // merchants only see their own report
        mvc.perform(get("/api/v1/ndr/report").param("from", t0.toString()).param("to", Instant.now().toString())
                .param("merchantId", m2.toString()).header("Authorization", TestJwt.merchant(m1)))
                .andExpect(status().isForbidden());
    }

    @Test
    void refusalTriggersImmediateRto() throws Exception {
        scanOk("FAST", "o1", trk, "OUT_FOR_DELIVERY", t(10), null);
        scanOk("FAST", "f1", trk, "DELIVERY_FAILED", t(20), "CUSTOMER_REFUSED");
        assertThat(statusOf(id)).isEqualTo("RTO_INITIATED");
    }

    @Test
    void concurrentDuplicateScansHaveExactlyOneEffect() throws Exception {
        List<Object> results = runConcurrently(40, i -> () ->
                body(scan(TestJwt.ops(), "FAST", "same-event", trk, "PICKED_UP", t(5), null)));
        Map<String, Long> outcomes = results.stream().map(o -> ((JsonNode) o).get("outcome").asText())
                .collect(Collectors.groupingBy(o -> o, Collectors.counting()));
        assertThat(outcomes).containsEntry("APPLIED", 1L).containsEntry("DUPLICATE", 39L);
        assertThat(count("SELECT count(*) FROM scan_event")).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM shipment_status_history WHERE to_status = 'PICKED_UP'")).isEqualTo(1);
    }

    @Test
    void concurrentDistinctScansConvergeOnLatestStatusWithoutRegression() throws Exception {
        String[] codes = {"PICKED_UP", "IN_TRANSIT", "AT_HUB", "OUT_FOR_DELIVERY"};
        List<Object> results = runConcurrently(24, i -> () ->
                body(scan(TestJwt.ops(), "FAST", "ev-" + i, trk, codes[i % 4], t(i), null)));
        assertThat(results).allMatch(o -> o instanceof JsonNode n && n.has("outcome"));
        assertThat(count("SELECT count(*) FROM scan_event")).isEqualTo(24);
        assertThat(statusOf(id)).isEqualTo("OUT_FOR_DELIVERY");
        assertThat(count("SELECT attempt_count FROM shipment WHERE id = ?::uuid", id)).isGreaterThanOrEqualTo(1);
        // history must be monotonic in rank
        List<Integer> ranks = jdbc.queryForList("""
                SELECT CASE to_status WHEN 'CREATED' THEN 0 WHEN 'ALLOCATED' THEN 10 WHEN 'PICKED_UP' THEN 20
                       WHEN 'IN_TRANSIT' THEN 30 WHEN 'AT_HUB' THEN 30 ELSE 40 END
                FROM shipment_status_history WHERE shipment_id = ?::uuid ORDER BY id
                """, Integer.class, id);
        for (int i = 1; i < ranks.size(); i++) {
            assertThat(ranks.get(i)).isGreaterThanOrEqualTo(ranks.get(i - 1));
        }
    }

    @Test
    void carrierWebhookRequiresValidHmac() throws Exception {
        String body = """
                {"carrierCode":"FAST","carrierEventId":"wh-1","awb":"%s","eventCode":"PICKED_UP","occurredAt":"%s"}
                """.formatted(jdbc.queryForObject("SELECT carrier_awb FROM shipment WHERE id = ?::uuid", String.class, id), t(1));
        long now = Instant.now().getEpochSecond();
        mvc.perform(post("/api/v1/carrier-webhooks/FAST/scans").contentType(MediaType.APPLICATION_JSON).content(body)
                        .header("X-Carrier-Signature", HmacSigner.signatureHeader("wrong", now, body)))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("INVALID_SIGNATURE"));
        mvc.perform(post("/api/v1/carrier-webhooks/FAST/scans").contentType(MediaType.APPLICATION_JSON).content(body)
                        .header("X-Carrier-Signature", HmacSigner.signatureHeader("carrier-secret-FAST", now - 3600, body)))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/api/v1/carrier-webhooks/FAST/scans").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/api/v1/carrier-webhooks/FAST/scans").contentType(MediaType.APPLICATION_JSON).content(body)
                        .header("X-Carrier-Signature", HmacSigner.signatureHeader("carrier-secret-FAST", now, body)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.outcome").value("APPLIED"));
        assertThat(statusOf(id)).isEqualTo("PICKED_UP");
    }

    @Test
    void ridersMayScanOwnFleetShipments() throws Exception {
        jdbc.update("UPDATE carrier SET active = false WHERE code = 'FAST'");
        carrierDirectory.invalidate();
        ownFleet("OWN", "8.00", 1, 100);
        JsonNode s = bookOk(m1, "ORD-OWN", "PREPAID", null);
        assertThat(s.get("carrier").get("code").asText()).isEqualTo("OWN");
        scan(TestJwt.rider(), "OWN", "r1", s.get("trackingNumber").asText(), "PICKED_UP", t(1), null)
                .andExpect(status().isOk()).andExpect(jsonPath("$.outcome").value("APPLIED"));
    }
}
