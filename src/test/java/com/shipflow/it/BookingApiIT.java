package com.shipflow.it;

import com.fasterxml.jackson.databind.JsonNode;
import com.shipflow.support.AbstractPostgresIT;
import com.shipflow.support.TestJwt;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class BookingApiIT extends AbstractPostgresIT {

    @Test
    void booksAllocatesCheapestCarrierAndConfirmsCarrierBooking() throws Exception {
        thirdParty("PRICEY", "25.00", 2, 100);
        thirdParty("CHEAP", "12.00", 2, 100);
        JsonNode s = bookOk(m1, "ORD-1", "PREPAID", null);

        assertThat(s.get("status").asText()).isEqualTo("ALLOCATED");
        assertThat(s.get("carrier").get("code").asText()).isEqualTo("CHEAP");
        assertThat(s.get("awb").asText()).startsWith("CHEAP-");
        assertThat(s.get("trackingNumber").asText()).matches("SF[0-9A-Z]{12}");
        assertThat(s.get("promisedDate").asText()).isNotBlank();
        assertThat(s.get("shippingCost").get("amount").decimalValue()).isEqualByComparingTo("12.00");

        JsonNode alloc = body(mvc.perform(get("/api/v1/shipments/{id}/allocation", s.get("id").asText())
                .header("Authorization", TestJwt.merchant(m1))).andExpect(status().isOk()));
        JsonNode exp = alloc.get("decisions").get(0).get("explanation");
        assertThat(exp.get("chosen").asText()).isEqualTo("CHEAP");
        assertThat(exp.get("ranked")).hasSize(2);
        assertThat(exp.get("ranked").get(0).get("rules").get("cost").get("score").asDouble()).isEqualTo(1.0);
        assertThat(exp.get("summary").asText()).contains("CHEAP");
        assertThat(alloc.get("carrierBookings").get(0).get("status").asText()).isEqualTo("CONFIRMED");
    }

    @Test
    void codShipmentIncludesCodFeeAndSkipsCarriersWithoutCod() throws Exception {
        carrier("NOCOD", "THIRD_PARTY", "SIMULATED_3PL", "5.00", 1, 100, false);
        thirdParty("CODOK", "10.00", 2, 100);
        JsonNode s = bookOk(m1, "ORD-COD", "COD", "300.00");
        assertThat(s.get("carrier").get("code").asText()).isEqualTo("CODOK");
        // 10.00 freight + max(1.5% of 300 = 4.50, 2.00) = 14.50
        assertThat(s.get("shippingCost").get("amount").decimalValue()).isEqualByComparingTo("14.50");
        JsonNode exp = body(mvc.perform(get("/api/v1/shipments/{id}/allocation", s.get("id").asText())
                .header("Authorization", TestJwt.merchant(m1)))).get("decisions").get(0).get("explanation");
        assertThat(exp.get("rejected").get(0).get("carrier").asText()).isEqualTo("NOCOD");
        assertThat(exp.get("rejected").get(0).get("filter").asText()).isEqualTo("cod");
    }

    @Test
    void idempotentReplayReturnsSameShipment() throws Exception {
        thirdParty("CHEAP", "12.00", 2, 100);
        String k = key();
        String json = shipmentJson("ORD-2", "PREPAID", null);
        String id1 = body(book(m1, k, json).andExpect(status().isCreated())).get("id").asText();
        String id2 = body(book(m1, k, json).andExpect(status().isCreated())
                .andExpect(header().string("Idempotent-Replayed", "true"))).get("id").asText();
        assertThat(id2).isEqualTo(id1);
        assertThat(count("SELECT count(*) FROM shipment")).isEqualTo(1);
    }

    @Test
    void sameKeyDifferentPayloadIs422() throws Exception {
        thirdParty("CHEAP", "12.00", 2, 100);
        String k = key();
        book(m1, k, shipmentJson("ORD-3", "PREPAID", null)).andExpect(status().isCreated());
        book(m1, k, shipmentJson("ORD-3B", "PREPAID", null)).andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REUSED"));
    }

    @Test
    void sameKeyDifferentMerchantsAreIndependent() throws Exception {
        thirdParty("CHEAP", "12.00", 2, 100);
        String k = key();
        book(m1, k, shipmentJson("ORD-X", "PREPAID", null)).andExpect(status().isCreated());
        book(m2, k, shipmentJson("ORD-X", "PREPAID", null)).andExpect(status().isCreated());
        assertThat(count("SELECT count(*) FROM shipment")).isEqualTo(2);
    }

    @Test
    void duplicateOrderRefWithNewKeyIs409() throws Exception {
        thirdParty("CHEAP", "12.00", 2, 100);
        bookOk(m1, "ORD-4", "PREPAID", null);
        book(m1, key(), shipmentJson("ORD-4", "PREPAID", null)).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DUPLICATE_ORDER"));
    }

    @Test
    void validationErrorsAre400WithFieldDetails() throws Exception {
        String bad = shipmentJson("ORD-5", "COD", null).replace("\"weightGrams\":1200", "\"weightGrams\":-1");
        book(m1, key(), bad).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors").isArray())
                .andExpect(header().exists("X-Correlation-Id"));
        book(m1, "short", shipmentJson("ORD-6", "PREPAID", null)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_IDEMPOTENCY_KEY"));
        mvc.perform(post("/api/v1/shipments").header("Authorization", TestJwt.merchant(m1))
                        .contentType(MediaType.APPLICATION_JSON).content(shipmentJson("ORD-7", "PREPAID", null)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void unserviceablePincodeIs422AndCurrencyMismatchIs422() throws Exception {
        thirdParty("CHEAP", "12.00", 2, 100);
        book(m1, key(), shipmentJson("ORD-8", "PREPAID", null).replace(PIN, "NOWHERE-1"))
                .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("UNSERVICEABLE"));
        book(m1, key(), shipmentJson("ORD-9", "PREPAID", null).replace("\"AED\"", "\"SAR\""))
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    void noCapacityLeavesShipmentCreatedWithExplanation() throws Exception {
        thirdParty("TINY", "12.00", 2, 1);
        bookOk(m1, "ORD-10", "PREPAID", null);
        JsonNode second = bookOk(m1, "ORD-11", "PREPAID", null);
        assertThat(second.get("status").asText()).isEqualTo("CREATED");
        assertThat(second.has("carrier")).isFalse();
        String outcome = jdbc.queryForObject("SELECT outcome FROM allocation_decision WHERE shipment_id = ?::uuid",
                String.class, second.get("id").asText());
        assertThat(outcome).isEqualTo("UNALLOCATED");
    }

    @Test
    void securityAndOwnership() throws Exception {
        thirdParty("CHEAP", "12.00", 2, 100);
        String id = bookOk(m1, "ORD-12", "PREPAID", null).get("id").asText();
        mvc.perform(get("/api/v1/shipments/{id}", id).header("Authorization", TestJwt.merchant(m2)))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("FORBIDDEN"));
        mvc.perform(get("/api/v1/shipments/{id}", id)).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/shipments/{id}", id).header("Authorization", TestJwt.ops())).andExpect(status().isOk());
        mvc.perform(post("/api/v1/shipments/{id}/cancel", id).header("Authorization", TestJwt.merchant(m2)))
                .andExpect(status().isForbidden());
        book(m1, key(), shipmentJson("ORD-13", "PREPAID", null)).andReturn();
        mvc.perform(post("/api/v1/shipments").header("Authorization", TestJwt.rider()).header("Idempotency-Key", key())
                        .contentType(MediaType.APPLICATION_JSON).content(shipmentJson("ORD-14", "PREPAID", null)))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/admin/carriers/CHEAP/scorecard").param("zone", ZONE)
                .header("Authorization", TestJwt.ops())).andExpect(status().isForbidden());
        String forged = "Bearer " + TestJwt.token("wrong-secret-wrong-secret-wrong-secret-123", "x", m1,
                java.time.Instant.now().plusSeconds(60), "MERCHANT");
        mvc.perform(get("/api/v1/shipments").header("Authorization", forged)).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/shipments/{id}", java.util.UUID.randomUUID()).header("Authorization", TestJwt.merchant(m1)))
                .andExpect(status().isNotFound());
    }

    @Test
    void listIsMerchantScopedPaginatedAndFiltered() throws Exception {
        thirdParty("CHEAP", "12.00", 2, 3);
        for (int i = 0; i < 5; i++) {
            bookOk(m1, "L-" + i, "PREPAID", null);
        }
        bookOk(m2, "OTHER-1", "PREPAID", null);
        JsonNode page0 = body(mvc.perform(get("/api/v1/shipments").param("size", "2")
                .header("Authorization", TestJwt.merchant(m1))).andExpect(status().isOk()));
        assertThat(page0.get("totalItems").asInt()).isEqualTo(5);
        assertThat(page0.get("totalPages").asInt()).isEqualTo(3);
        assertThat(page0.get("items")).hasSize(2);
        JsonNode created = body(mvc.perform(get("/api/v1/shipments").param("status", "CREATED")
                .header("Authorization", TestJwt.merchant(m1))));
        assertThat(created.get("totalItems").asInt()).isEqualTo(2); // capacity 3: L-0..L-2 allocated
        JsonNode byCarrier = body(mvc.perform(get("/api/v1/shipments").param("carrier", "CHEAP")
                .header("Authorization", TestJwt.merchant(m1))));
        assertThat(byCarrier.get("totalItems").asInt()).isEqualTo(3);
        mvc.perform(get("/api/v1/shipments").param("merchantId", m2.toString())
                .header("Authorization", TestJwt.merchant(m1))).andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/shipments").param("size", "1000")
                .header("Authorization", TestJwt.merchant(m1))).andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/shipments").param("status", "NOPE")
                .header("Authorization", TestJwt.merchant(m1))).andExpect(status().isBadRequest());
    }

    @Test
    void cancelReleasesCapacityAndIsRejectedAfterPickup() throws Exception {
        long c = thirdParty("CHEAP", "12.00", 2, 1);
        JsonNode s = bookOk(m1, "ORD-15", "PREPAID", null);
        String id = s.get("id").asText();
        mvc.perform(post("/api/v1/shipments/{id}/cancel", id).header("Authorization", TestJwt.merchant(m1))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"customer changed mind\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("CANCELLED"));
        assertThat(count("SELECT reserved FROM carrier_capacity WHERE carrier_id = ?", c)).isZero();
        assertThat(count("SELECT count(*) FROM carrier_booking WHERE status = 'CANCELLED'")).isEqualTo(1);
        // capacity is usable again
        assertThat(bookOk(m1, "ORD-16", "PREPAID", null).get("status").asText()).isEqualTo("ALLOCATED");
        String id2 = jdbc.queryForObject("SELECT id::text FROM shipment WHERE merchant_order_ref = 'ORD-16'", String.class);
        String trk = jdbc.queryForObject("SELECT tracking_number FROM shipment WHERE id = ?::uuid", String.class, id2);
        scanOk("CHEAP", "e1", trk, "PICKED_UP", java.time.Instant.now(), null);
        mvc.perform(post("/api/v1/shipments/{id}/cancel", id2).header("Authorization", TestJwt.merchant(m1)))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("INVALID_STATE_TRANSITION"));
    }

    @Test
    void publicTrackingExposesNoPii() throws Exception {
        thirdParty("CHEAP", "12.00", 2, 100);
        JsonNode s = bookOk(m1, "ORD-17", "COD", "99.00");
        String trk = s.get("trackingNumber").asText();
        String json = mvc.perform(get("/api/v1/tracking/{t}", trk)).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ALLOCATED"))
                .andExpect(jsonPath("$.events.length()").value(2))
                .andReturn().getResponse().getContentAsString();
        assertThat(json).doesNotContain("Aisha").doesNotContain("971501234567").doesNotContain("Villa");
        mvc.perform(get("/api/v1/tracking/SF000000000000")).andExpect(status().isNotFound());
    }
}
