package com.shipflow.it;

import com.fasterxml.jackson.databind.JsonNode;
import com.shipflow.support.AbstractPostgresIT;
import com.shipflow.support.TestJwt;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class AdminAndRiskIT extends AbstractPostgresIT {

    @Test
    void adminOnboardsMerchantAndCarrierThenBookingUsesThem() throws Exception {
        JsonNode merchant = body(mvc.perform(post("/api/v1/admin/merchants").header("Authorization", TestJwt.admin())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"Centrepoint\",\"countryCode\":\"AE\",\"webhookUrl\":\"https://example.invalid/hook\"}"))
                .andExpect(status().isCreated()));
        assertThat(merchant.get("webhookSecret").asText()).startsWith("whsec_");
        UUID mid = UUID.fromString(merchant.get("id").asText());

        String carrier = """
                {"code":"QUICK_3PL","name":"Quick","kind":"THIRD_PARTY","integration":"SIMULATED_3PL",
                 "zones":[{"zoneCode":"%s","slaDays":1,"codSupported":true,"maxWeightGrams":20000,"currency":"AED",
                           "extraPer500g":"1.50","codFeePercent":"2.0","codFeeMin":"3.00","defaultDailyCapacity":5,
                           "slabs":[{"upToGrams":1000,"price":"9.00"},{"upToGrams":5000,"price":"14.00"}]}]}
                """.formatted(ZONE);
        JsonNode created = body(mvc.perform(post("/api/v1/admin/carriers").header("Authorization", TestJwt.admin())
                .contentType(MediaType.APPLICATION_JSON).content(carrier)).andExpect(status().isCreated()));
        assertThat(created.get("inboundWebhookSecret").asText()).isNotBlank();
        mvc.perform(post("/api/v1/admin/carriers").header("Authorization", TestJwt.admin())
                .contentType(MediaType.APPLICATION_JSON).content(carrier)).andExpect(status().isConflict());

        JsonNode s = bookOk(mid, "ADM-1", "PREPAID", null); // 1200 g => 5 kg slab
        assertThat(s.get("carrier").get("code").asText()).isEqualTo("QUICK_3PL");
        assertThat(s.get("shippingCost").get("amount").decimalValue()).isEqualByComparingTo("14.00");
        assertThat(count("SELECT count(*) FROM outbox_event WHERE merchant_id = ?", mid)).isGreaterThan(0);

        // capacity override to 0 for the service date => next booking is unallocated
        String date = jdbc.queryForObject("SELECT service_date::text FROM carrier_capacity", String.class);
        mvc.perform(put("/api/v1/admin/carriers/QUICK_3PL/capacity").header("Authorization", TestJwt.admin())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"zoneCode\":\"%s\",\"date\":\"%s\",\"capacity\":1}".formatted(ZONE, date)))
                .andExpect(status().isNoContent());
        assertThat(bookOk(mid, "ADM-2", "PREPAID", null).get("status").asText()).isEqualTo("CREATED");

        // simulator switch through the API drives failover behaviour
        mvc.perform(put("/api/v1/admin/simulator/carriers/QUICK_3PL").header("Authorization", TestJwt.admin())
                .contentType(MediaType.APPLICATION_JSON).content("{\"mode\":\"FAIL\"}")).andExpect(status().isNoContent());
        assertThat(simulator.behaviourOf("QUICK_3PL").mode().name()).isEqualTo("FAIL");
    }

    @Test
    void riskyCodShipmentGetsExplainedHighRiskAndActions() throws Exception {
        thirdParty("FAST", "10.00", 2, 100);
        // poor area history: 8 of 10 recent attempts in the pincode failed
        jdbc.update("INSERT INTO pincode_delivery_stats (pincode, attempts, failures) VALUES (?, 10, 8)", PIN);
        String json = shipmentJson("RISK-1", "COD", "900.00")
                .replace("\"addressLine1\":\"Villa 12, Street 4B, Al Barsha 2\"", "\"addressLine1\":\"near mosque\"")
                .replace("\"landmark\":\"near Mall of the Emirates\",", "");
        JsonNode s = body(book(m1, key(), json).andExpect(status().isCreated()));
        JsonNode risk = s.get("risk");
        assertThat(risk.get("band").asText()).isEqualTo("HIGH");
        JsonNode factors = risk.get("assessment").get("factors");
        assertThat(factors).hasSize(4);
        int sum = 0;
        for (JsonNode f : factors) {
            sum += f.get("points").asInt();
            assertThat(f.get("reason").asText()).isNotBlank();
        }
        assertThat(sum).isEqualTo(risk.get("score").asInt());
        assertThat(risk.get("assessment").get("actions").toString()).contains("REQUIRE_DELIVERY_OTP", "PREPAID_CONVERSION_NUDGE");
        // HIGH risk doubles the first-attempt weight in the allocation explanation
        double w = jdbc.queryForObject("""
                SELECT (explanation->'ranked'->0->'rules'->'first-attempt'->>'weight')::float8
                  FROM allocation_decision WHERE shipment_id = ?::uuid
                """, Double.class, s.get("id").asText());
        assertThat(w).isEqualTo(0.2);
    }

    @Test
    void promiseDateSkipsWeekendAndHolidays() throws Exception {
        thirdParty("FAST", "10.00", 3, 100);
        ZoneId dubai = ZoneId.of("Asia/Dubai");
        LocalDate today = LocalDate.now(dubai);
        // declare the next 3 days holidays: the promise must land after them
        for (int i = 1; i <= 3; i++) {
            jdbc.update("INSERT INTO holiday (country_code, holiday_date, name) VALUES ('AE', ?, 'test holiday')", today.plusDays(i));
        }
        networkDirectory.invalidateCalendars();
        JsonNode s = bookOk(m1, "EDD-1", "PREPAID", null);
        LocalDate promised = LocalDate.parse(s.get("promisedDate").asText());
        assertThat(promised).isAfter(today.plusDays(3));
        assertThat(promised.getDayOfWeek().getValue()).isLessThan(6); // AE weekend = Sat/Sun
        JsonNode edd = body(mvc.perform(get("/api/v1/shipments/{id}", s.get("id").asText())
                .header("Authorization", TestJwt.merchant(m1)))).get("edd");
        assertThat(edd.get("transitSource").asText()).isEqualTo("CARRIER_SLA");
        assertThat(edd.get("transitBusinessDays").asInt()).isEqualTo(3);
        assertThat(edd.get("nonWorkingDaysSkipped").asInt()).isGreaterThanOrEqualTo(3);
    }

    @Test
    void opsCanForceRtoAndReallocate() throws Exception {
        thirdParty("A1", "10.00", 2, 100);
        thirdParty("B1", "12.00", 2, 100);
        JsonNode s = bookOk(m1, "OPS-1", "PREPAID", null);
        String id = s.get("id").asText();
        assertThat(s.get("carrier").get("code").asText()).isEqualTo("A1");
        JsonNode moved = body(mvc.perform(post("/api/v1/shipments/{id}/reallocate", id).header("Authorization", TestJwt.ops())
                .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"A1 strike\",\"excludeCarriers\":[\"A1\"]}"))
                .andExpect(status().isOk()));
        assertThat(moved.get("carrier").get("code").asText()).isEqualTo("B1");
        assertThat(count("SELECT coalesce(sum(reserved),0) FROM carrier_capacity c JOIN carrier k ON k.id=c.carrier_id WHERE k.code='A1'"))
                .isZero();
        assertThat(count("SELECT count(*) FROM carrier_booking WHERE status = 'CANCELLED'")).isEqualTo(1);
        mvc.perform(post("/api/v1/shipments/{id}/reallocate", id).header("Authorization", TestJwt.merchant(m1)))
                .andExpect(status().isForbidden());
        String trk = s.get("trackingNumber").asText();
        Instant t = Instant.now().minus(1, ChronoUnit.HOURS);
        scanOk("B1", "o", trk, "OUT_FOR_DELIVERY", t, null);
        mvc.perform(post("/api/v1/shipments/{id}/rto", id).header("Authorization", TestJwt.ops())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"merchant request\"}"))
                .andExpect(status().isConflict()); // RTO only after a failed attempt
        scanOk("B1", "f", trk, "DELIVERY_FAILED", t.plusSeconds(60), "CUSTOMER_UNAVAILABLE");
        mvc.perform(post("/api/v1/shipments/{id}/rto", id).header("Authorization", TestJwt.ops())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"merchant request\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("RTO_INITIATED"));
    }

    @Test
    void actuatorHealthIsPublicMetricsNeedAdmin() throws Exception {
        mvc.perform(get("/actuator/health")).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("UP"));
        mvc.perform(get("/actuator/prometheus")).andExpect(status().isUnauthorized());
        mvc.perform(get("/actuator/metrics").header("Authorization", TestJwt.ops())).andExpect(status().isForbidden());
        mvc.perform(get("/actuator/metrics").header("Authorization", TestJwt.admin())).andExpect(status().isOk());
    }
}
