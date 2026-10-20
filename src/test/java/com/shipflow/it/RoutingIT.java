package com.shipflow.it;

import com.fasterxml.jackson.databind.JsonNode;
import com.shipflow.support.AbstractPostgresIT;
import com.shipflow.support.TestJwt;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Random;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class RoutingIT extends AbstractPostgresIT {

    @Test
    void plansCapacityBoundRoutesThatBeatTheNaiveBaseline() throws Exception {
        ownFleet("OWN", "8.00", 1, 500);
        Random rnd = new Random(7);
        for (int i = 0; i < 60; i++) {
            double lat = 25.05 + rnd.nextDouble() * 0.25;
            double lng = 55.10 + rnd.nextDouble() * 0.30;
            String json = shipmentJson("R-" + i, "PREPAID", null)
                    .replace("\"pincode\":\"" + PIN + "\"", "\"pincode\":\"" + PIN + "\",\"lat\":" + lat + ",\"lng\":" + lng);
            book(m1, key(), json).andExpect(status().isCreated());
        }
        // fixture shortcut: put shipments on the road (scan flow is covered elsewhere)
        jdbc.update("UPDATE shipment SET status = 'OUT_FOR_DELIVERY', status_rank = 40 WHERE merchant_order_ref LIKE 'R-%'");
        for (int r = 1; r <= 4; r++) {
            jdbc.update("INSERT INTO rider (code, name, hub_id, capacity) VALUES (?, ?, ?, 20)", "RID-" + r, "Rider " + r, hubId);
        }
        JsonNode plan = body(mvc.perform(post("/api/v1/hubs/{hub}/route-plans", HUB).param("radiusKm", "60")
                .header("Authorization", TestJwt.ops())).andExpect(status().isCreated()));
        assertThat(plan.get("stops").asInt()).isEqualTo(60);
        assertThat(plan.get("unassigned").asInt()).isZero();
        assertThat(plan.get("optimizedKm").asDouble()).isLessThan(plan.get("baselineKm").asDouble());
        assertThat(plan.get("savingsPercent").asDouble()).isGreaterThan(10.0);
        Set<String> seen = new HashSet<>();
        for (JsonNode route : plan.get("routes")) {
            assertThat(route.get("stops").asInt()).isLessThanOrEqualTo(20);
            route.get("sequence").forEach(s -> assertThat(seen.add(s.get("shipmentId").asText())).isTrue());
        }
        assertThat(seen).hasSize(60);
        assertThat(count("SELECT count(*) FROM route_stop")).isEqualTo(60);
        mvc.perform(post("/api/v1/hubs/{hub}/route-plans", HUB).header("Authorization", TestJwt.merchant(m1)))
                .andExpect(status().isForbidden());
    }
}
