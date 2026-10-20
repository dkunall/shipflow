package com.shipflow.it;

import com.fasterxml.jackson.databind.JsonNode;
import com.shipflow.support.AbstractPostgresIT;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

class ConcurrencyIT extends AbstractPostgresIT {

    @Test
    void twoHundredConcurrentBookingsNeverExceedCarrierCapacity() throws Exception {
        long a = thirdParty("ALPHA", "10.00", 2, 60);
        long b = thirdParty("BRAVO", "11.00", 2, 40);
        List<Object> results = runConcurrently(200, i -> () ->
                book(m1, key(), shipmentJson("CC-" + i, "PREPAID", null)).andReturn());
        assertThat(results).allMatch(r -> r instanceof MvcResult m && m.getResponse().getStatus() == 201);
        Map<String, Long> byStatus = results.stream().map(r -> {
            try {
                return mapper.readTree(((MvcResult) r).getResponse().getContentAsString()).get("status").asText();
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        }).collect(Collectors.groupingBy(s -> s, Collectors.counting()));
        assertThat(byStatus).containsEntry("ALLOCATED", 100L).containsEntry("CREATED", 100L);
        assertThat(count("SELECT coalesce(sum(reserved),0) FROM carrier_capacity WHERE carrier_id = ?", a)).isEqualTo(60);
        assertThat(count("SELECT coalesce(sum(reserved),0) FROM carrier_capacity WHERE carrier_id = ?", b)).isEqualTo(40);
        assertThat(count("SELECT count(*) FROM carrier_capacity WHERE reserved > capacity")).isZero();
        assertThat(count("SELECT count(*) FROM shipment WHERE status = 'ALLOCATED' AND carrier_id = ?", a)).isEqualTo(60);
        assertThat(count("SELECT count(*) FROM allocation_decision WHERE outcome = 'RESERVED'")).isEqualTo(100);
        assertThat(count("SELECT count(*) FROM carrier_booking WHERE status = 'CONFIRMED'")).isEqualTo(100);
        assertThat(simulator.consignmentsCreated("ALPHA") + simulator.consignmentsCreated("BRAVO")).isEqualTo(100);
    }

    @Test
    void concurrentRequestsWithSameIdempotencyKeyCreateOneShipment() throws Exception {
        thirdParty("ALPHA", "10.00", 2, 500);
        String k = key();
        String json = shipmentJson("SAME-1", "PREPAID", null);
        List<Object> results = runConcurrently(50, i -> () -> book(m1, k, json).andReturn());
        Set<Integer> statuses = results.stream().map(r -> ((MvcResult) r).getResponse().getStatus()).collect(Collectors.toSet());
        assertThat(statuses).containsOnly(201);
        Set<String> ids = results.stream().map(r -> {
            try {
                JsonNode n = mapper.readTree(((MvcResult) r).getResponse().getContentAsString());
                return n.get("id").asText();
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        }).collect(Collectors.toSet());
        assertThat(ids).hasSize(1);
        assertThat(results.stream().filter(r -> "true".equals(((MvcResult) r).getResponse().getHeader("Idempotent-Replayed"))))
                .hasSize(49);
        assertThat(count("SELECT count(*) FROM shipment")).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM carrier_booking")).isEqualTo(1);
        assertThat(simulator.consignmentsCreated("ALPHA")).isEqualTo(1);
    }
}
