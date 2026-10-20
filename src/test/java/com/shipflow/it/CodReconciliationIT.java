package com.shipflow.it;

import com.fasterxml.jackson.databind.JsonNode;
import com.shipflow.support.AbstractPostgresIT;
import com.shipflow.support.TestJwt;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class CodReconciliationIT extends AbstractPostgresIT {

    private String deliveredCod(String ref, String amount) throws Exception {
        JsonNode s = bookOk(m1, ref, "COD", amount);
        String trk = s.get("trackingNumber").asText();
        Instant t = Instant.now().minus(1, ChronoUnit.HOURS);
        scanOk("FAST", ref + "-1", trk, "OUT_FOR_DELIVERY", t, null);
        scanOk("FAST", ref + "-2", trk, "DELIVERED", t.plusSeconds(60), null);
        return trk;
    }

    private String remittance(String ref, Map<String, String> lines) {
        StringBuilder sb = new StringBuilder();
        lines.forEach((k, v) -> sb.append(sb.isEmpty() ? "" : ",").append("{\"trackingNumber\":\"%s\",\"amount\":\"%s\"}".formatted(k, v)));
        return """
                {"carrierCode":"FAST","remittanceRef":"%s","currency":"AED","receivedAt":"%s","lines":[%s]}
                """.formatted(ref, Instant.now(), sb);
    }

    @Test
    void reconcilesMatchedShortExcessMissingExactlyAndIdempotently() throws Exception {
        thirdParty("FAST", "10.00", 2, 100);
        String a = deliveredCod("A", "100.00");
        String b = deliveredCod("B", "250.50");
        String c = deliveredCod("C", "80.00");
        String d = deliveredCod("D", "60.00");
        bookOk(m1, "P", "PREPAID", null); // prepaid: never expected
        Map<String, String> lines = new HashMap<>();
        lines.put(a, "100.00");
        lines.put(b, "250.00");
        lines.put(c, "80.10");
        lines.put("SFZZZZZZZZZZZZ", "15.00");
        mvc.perform(post("/api/v1/cod/remittances").header("Authorization", TestJwt.ops())
                .contentType(MediaType.APPLICATION_JSON).content(remittance("REM-1", lines))).andExpect(status().isCreated());
        mvc.perform(post("/api/v1/cod/remittances").header("Authorization", TestJwt.ops())
                        .contentType(MediaType.APPLICATION_JSON).content(remittance("REM-1", lines)))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("DUPLICATE_REMITTANCE"));

        String period = """
                {"carrierCode":"FAST","from":"%s","to":"%s"}
                """.formatted(Instant.now().minus(1, ChronoUnit.DAYS), Instant.now().plus(1, ChronoUnit.DAYS));
        JsonNode run1 = body(mvc.perform(post("/api/v1/cod/reconciliations").header("Authorization", TestJwt.ops())
                .contentType(MediaType.APPLICATION_JSON).content(period)).andExpect(status().isOk()));
        assertThat(run1.get("matched").asInt()).isEqualTo(1);
        assertThat(run1.get("shortCount").asInt()).isEqualTo(1);
        assertThat(run1.get("excess").asInt()).isEqualTo(2);
        assertThat(run1.get("missing").asInt()).isEqualTo(1);
        assertThat(run1.get("expectedTotal").decimalValue()).isEqualByComparingTo("490.50");
        assertThat(run1.get("remittedTotal").decimalValue()).isEqualByComparingTo("445.10");

        JsonNode run2 = body(mvc.perform(post("/api/v1/cod/reconciliations").header("Authorization", TestJwt.ops())
                .contentType(MediaType.APPLICATION_JSON).content(period)).andExpect(status().isOk()));
        assertThat(run2.get("runId").asLong()).isEqualTo(run1.get("runId").asLong());
        assertThat(run2.get("runCount").asInt()).isEqualTo(2);
        assertThat(run2.get("matched").asInt()).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM cod_reconciliation_result")).isEqualTo(5);

        JsonNode detail = body(mvc.perform(get("/api/v1/cod/reconciliations/{id}", run1.get("runId").asLong())
                .header("Authorization", TestJwt.ops())).andExpect(status().isOk()));
        Map<String, JsonNode> byTrk = new HashMap<>();
        detail.get("results").forEach(r -> byTrk.put(r.get("trackingNumber").asText(), r));
        assertThat(byTrk.get(b).get("outcome").asText()).isEqualTo("SHORT");
        assertThat(byTrk.get(b).get("difference").decimalValue()).isEqualByComparingTo("-0.50");
        assertThat(byTrk.get(c).get("difference").decimalValue()).isEqualByComparingTo("0.10");
        assertThat(byTrk.get(d).get("outcome").asText()).isEqualTo("MISSING");
        assertThat(byTrk.get("SFZZZZZZZZZZZZ").get("outcome").asText()).isEqualTo("EXCESS");

        // late remittance for D arrives: re-running the same period now matches it
        mvc.perform(post("/api/v1/cod/remittances").header("Authorization", TestJwt.ops())
                .contentType(MediaType.APPLICATION_JSON).content(remittance("REM-2", Map.of(d, "60.00"))))
                .andExpect(status().isCreated());
        JsonNode run3 = body(mvc.perform(post("/api/v1/cod/reconciliations").header("Authorization", TestJwt.ops())
                .contentType(MediaType.APPLICATION_JSON).content(period)));
        assertThat(run3.get("matched").asInt()).isEqualTo(2);
        assertThat(run3.get("missing").asInt()).isZero();
        mvc.perform(post("/api/v1/cod/reconciliations").header("Authorization", TestJwt.merchant(m1))
                .contentType(MediaType.APPLICATION_JSON).content(period)).andExpect(status().isForbidden());
    }

    @Test
    void concurrentReRunsOfTheSamePeriodConverge() throws Exception {
        thirdParty("FAST", "10.00", 2, 100);
        String a = deliveredCod("A", "42.42");
        mvc.perform(post("/api/v1/cod/remittances").header("Authorization", TestJwt.ops())
                .contentType(MediaType.APPLICATION_JSON).content(remittance("R", Map.of(a, "42.42"))));
        String period = """
                {"carrierCode":"FAST","from":"%s","to":"%s"}
                """.formatted(Instant.now().minus(1, ChronoUnit.DAYS), Instant.now().plus(1, ChronoUnit.DAYS));
        List<Object> results = runConcurrently(8, i -> () -> mvc.perform(post("/api/v1/cod/reconciliations")
                .header("Authorization", TestJwt.ops()).contentType(MediaType.APPLICATION_JSON).content(period))
                .andReturn().getResponse().getStatus());
        assertThat(results).containsOnly(200);
        assertThat(count("SELECT count(*) FROM cod_reconciliation_run")).isEqualTo(1);
        assertThat(count("SELECT run_count FROM cod_reconciliation_run")).isEqualTo(8);
        assertThat(count("SELECT count(*) FROM cod_reconciliation_result")).isEqualTo(1);
        assertThat(count("SELECT matched FROM cod_reconciliation_run")).isEqualTo(1);
    }
}
