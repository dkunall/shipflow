package com.shipflow.it;

import com.fasterxml.jackson.databind.JsonNode;
import com.shipflow.support.AbstractPostgresIT;
import com.shipflow.support.TestJwt;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Feature A: delivery outcomes feed rolling scorecards which shift future allocations to the better carrier. */
class ScorecardLearningIT extends AbstractPostgresIT {

    @Test
    void allocationShiftsTowardTheCarrierThatActuallyDelivers() throws Exception {
        thirdParty("ALPHA", "10.00", 2, 20);   // slightly cheaper: wins on day one
        thirdParty("BRAVO", "10.50", 2, 200);
        assertThat(bookOk(m1, "PROBE-0", "PREPAID", null).get("carrier").get("code").asText()).isEqualTo("ALPHA");

        // history: the capacity-headroom rule spreads 39 more shipments across both carriers
        List<JsonNode> alpha = new ArrayList<>();
        List<JsonNode> bravo = new ArrayList<>();
        for (int i = 0; i < 39; i++) {
            JsonNode s = bookOk(m1, "H-" + i, "PREPAID", null);
            (s.get("carrier").get("code").asText().equals("ALPHA") ? alpha : bravo).add(s);
        }
        assertThat(alpha).hasSizeGreaterThanOrEqualTo(5);
        assertThat(bravo).hasSizeGreaterThanOrEqualTo(5);
        Instant t = Instant.now().minus(3, ChronoUnit.HOURS);
        int e = 0;
        for (JsonNode s : alpha) { // ALPHA: customers refuse => RTO
            String trk = s.get("trackingNumber").asText();
            scanOk("ALPHA", "a" + e++, trk, "OUT_FOR_DELIVERY", t, null);
            scanOk("ALPHA", "a" + e++, trk, "DELIVERY_FAILED", t.plusSeconds(60), "CUSTOMER_REFUSED");
            scanOk("ALPHA", "a" + e++, trk, "RTO_IN_TRANSIT", t.plusSeconds(120), null);
            scanOk("ALPHA", "a" + e++, trk, "RTO_DELIVERED", t.plusSeconds(180), null);
        }
        for (JsonNode s : bravo) { // BRAVO: delivered first time, on time
            String trk = s.get("trackingNumber").asText();
            scanOk("BRAVO", "b" + e++, trk, "OUT_FOR_DELIVERY", t, null);
            scanOk("BRAVO", "b" + e++, trk, "DELIVERED", t.plusSeconds(60), null);
        }
        flushStats();
        JsonNode card = body(mvc.perform(get("/api/v1/admin/carriers/ALPHA/scorecard").param("zone", ZONE)
                .header("Authorization", TestJwt.admin())).andExpect(status().isOk()));
        assertThat(card.get("raw").get("rto").asInt()).isEqualTo(alpha.size());
        assertThat(card.get("smoothed").get("rtoRate").asDouble()).isGreaterThan(0.2);

        // give ALPHA plenty of capacity again for the next service date so only the scorecard differs
        String serviceDate = jdbc.queryForObject("SELECT max(service_date)::text FROM carrier_capacity", String.class);
        mvc.perform(put("/api/v1/admin/carriers/ALPHA/capacity").header("Authorization", TestJwt.admin())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"zoneCode\":\"%s\",\"date\":\"%s\",\"capacity\":200}".formatted(ZONE, serviceDate)))
                .andExpect(status().isNoContent());

        JsonNode after = bookOk(m1, "PROBE-1", "PREPAID", null);
        assertThat(after.get("carrier").get("code").asText()).isEqualTo("BRAVO");
        JsonNode exp = jdbc.queryForObject("SELECT explanation::text FROM allocation_decision WHERE shipment_id = ?::uuid",
                (rs, i) -> {
                    try {
                        return mapper.readTree(rs.getString(1));
                    } catch (Exception ex) {
                        throw new IllegalStateException(ex);
                    }
                }, after.get("id").asText());
        JsonNode ranked = exp.get("ranked");
        assertThat(ranked.get(0).get("carrier").asText()).isEqualTo("BRAVO");
        assertThat(ranked.get(1).get("carrier").asText()).isEqualTo("ALPHA");
        assertThat(ranked.get(1).get("rules").get("cost").get("score").asDouble())
                .isGreaterThan(ranked.get(0).get("rules").get("cost").get("score").asDouble()); // ALPHA still cheaper
        assertThat(ranked.get(0).get("rules").get("rto").get("score").asDouble())
                .isGreaterThan(ranked.get(1).get("rules").get("rto").get("score").asDouble());
    }
}
