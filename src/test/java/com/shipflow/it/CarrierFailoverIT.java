package com.shipflow.it;

import com.fasterxml.jackson.databind.JsonNode;
import com.shipflow.booking.api.CreateShipmentRequest;
import com.shipflow.booking.application.ShipmentBookingService;
import com.shipflow.booking.carrier.BookingOrchestrator;
import com.shipflow.booking.carrier.BookingReconciler;
import com.shipflow.carrier.port.SimulatedCarrierNetwork.Mode;
import com.shipflow.support.AbstractPostgresIT;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class CarrierFailoverIT extends AbstractPostgresIT {
    @Autowired BookingReconciler reconciler;
    @Autowired BookingOrchestrator orchestrator;
    @Autowired ShipmentBookingService bookingService;

    private long cheap;
    private long pricey;

    private void carriers() {
        cheap = thirdParty("CHEAP", "10.00", 2, 50);
        pricey = thirdParty("PRICEY", "20.00", 2, 50);
    }

    private int reserved(long carrierId) {
        return count("SELECT coalesce(sum(reserved),0) FROM carrier_capacity WHERE carrier_id = ?", carrierId);
    }

    @Test
    void failingCarrierFailsOverToNextBestAndReleasesCapacity() throws Exception {
        carriers();
        simulator.configure("CHEAP", Mode.FAIL, Duration.ZERO);
        JsonNode s = bookOk(m1, "F-1", "PREPAID", null);
        assertThat(s.get("status").asText()).isEqualTo("ALLOCATED");
        assertThat(s.get("carrier").get("code").asText()).isEqualTo("PRICEY");
        assertThat(s.get("awb").asText()).startsWith("PRICEY-");
        assertThat(reserved(cheap)).isZero();
        assertThat(reserved(pricey)).isEqualTo(1);
        assertThat(simulator.consignmentsCreated("CHEAP")).isZero();
        assertThat(jdbc.queryForList("SELECT status FROM carrier_booking ORDER BY id", String.class))
                .containsExactly("FAILED", "CONFIRMED");
        assertThat(jdbc.queryForList("SELECT outcome FROM allocation_decision ORDER BY id", String.class))
                .containsExactly("RELEASED", "RESERVED");
        String lastExplanation = jdbc.queryForObject(
                "SELECT explanation->>'trigger' FROM allocation_decision ORDER BY id DESC LIMIT 1", String.class);
        assertThat(lastExplanation).startsWith("FAILOVER from CHEAP");
    }

    @Test
    void businessRejectionAlsoFailsOver() throws Exception {
        carriers();
        simulator.configure("CHEAP", Mode.REJECT, Duration.ZERO);
        assertThat(bookOk(m1, "F-2", "PREPAID", null).get("carrier").get("code").asText()).isEqualTo("PRICEY");
    }

    @Test
    void allCarriersFailingLeavesShipmentUnallocatedWithNoCapacityHeld() throws Exception {
        carriers();
        simulator.configure("CHEAP", Mode.FAIL, Duration.ZERO);
        simulator.configure("PRICEY", Mode.FAIL, Duration.ZERO);
        JsonNode s = bookOk(m1, "F-3", "PREPAID", null);
        assertThat(s.get("status").asText()).isEqualTo("CREATED");
        assertThat(reserved(cheap) + reserved(pricey)).isZero();
        assertThat(count("SELECT count(*) FROM allocation_decision WHERE outcome = 'RESERVED'")).isZero();
        assertThat(jdbc.queryForObject("SELECT outcome FROM allocation_decision ORDER BY id DESC LIMIT 1", String.class))
                .isEqualTo("UNALLOCATED");
    }

    @Test
    void transientCarrierErrorIsRetriedInsteadOfFailingOver() throws Exception {
        carriers();
        simulator.configure("CHEAP", Mode.FLAKY, Duration.ZERO);
        JsonNode s = bookOk(m1, "R-1", "PREPAID", null);
        assertThat(s.get("carrier").get("code").asText()).isEqualTo("CHEAP");
        assertThat(s.get("awb").asText()).startsWith("CHEAP-");
        assertThat(simulator.calls("CHEAP")).isEqualTo(2);
        assertThat(simulator.consignmentsCreated("CHEAP")).isEqualTo(1);
        assertThat(simulator.calls("PRICEY")).isZero();
    }

    @Test
    void circuitOpensAndStopsCallingTheBrokenCarrier() throws Exception {
        carriers();
        simulator.configure("CHEAP", Mode.FAIL, Duration.ZERO);
        for (int i = 0; i < 12; i++) {
            assertThat(bookOk(m1, "CB-" + i, "PREPAID", null).get("carrier").get("code").asText()).isEqualTo("PRICEY");
        }
        // breaker min-calls = 5 at 50% threshold: after 5 failed calls CHEAP is filtered out without being called.
        // Each logical call is 2 attempts (one retry on a technical failure), so the carrier saw 10 requests.
        assertThat(simulator.calls("CHEAP")).isEqualTo(10);
        assertThat(breakers.forCarrier("CHEAP").state().name()).isEqualTo("OPEN");
        String rejected = jdbc.queryForObject("""
                SELECT explanation->'rejected'->0->>'filter' FROM allocation_decision ORDER BY id DESC LIMIT 1
                """, String.class);
        assertThat(rejected).isEqualTo("circuit-breaker");
        assertThat(reserved(cheap)).isZero();
    }

    @Test
    void timeoutAfterCarrierBookedIsReconciledWithoutDoubleBooking() throws Exception {
        carriers();
        simulator.configure("CHEAP", Mode.TIMEOUT_BOOKED, Duration.ZERO);
        JsonNode s = bookOk(m1, "T-1", "PREPAID", null);
        String id = s.get("id").asText();
        // no failover on an uncertain outcome: that could create two consignments
        assertThat(s.get("carrier").get("code").asText()).isEqualTo("CHEAP");
        assertThat(s.has("awb")).isFalse();
        assertThat(simulator.calls("PRICEY")).isZero();
        assertThat(jdbc.queryForObject("SELECT status FROM carrier_booking", String.class)).isEqualTo("UNKNOWN");

        simulator.configure("CHEAP", Mode.OK, Duration.ZERO);
        BookingReconciler.Report report = reconciler.runOnce(Duration.ZERO);
        assertThat(report.confirmed()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT carrier_awb FROM shipment WHERE id = ?::uuid", String.class, id))
                .startsWith("CHEAP-");
        assertThat(simulator.consignmentsCreated("CHEAP")).isEqualTo(1);
        assertThat(reconciler.runOnce(Duration.ZERO).confirmed()).isZero(); // idempotent re-run
    }

    @Test
    void timeoutWithNoBookingAtCarrierFailsOverOnReconcile() throws Exception {
        carriers();
        simulator.configure("CHEAP", Mode.TIMEOUT_LOST, Duration.ZERO);
        String id = bookOk(m1, "T-2", "PREPAID", null).get("id").asText();
        assertThat(reserved(cheap)).isEqualTo(1);

        BookingReconciler.Report report = reconciler.runOnce(Duration.ZERO);
        assertThat(report.failedOver()).isEqualTo(1);
        assertThat(jdbc.queryForObject("""
                SELECT c.code FROM shipment s JOIN carrier c ON c.id = s.carrier_id WHERE s.id = ?::uuid
                """, String.class, id)).isEqualTo("PRICEY");
        assertThat(jdbc.queryForObject("SELECT carrier_awb FROM shipment WHERE id = ?::uuid", String.class, id))
                .startsWith("PRICEY-");
        assertThat(reserved(cheap)).isZero();
        assertThat(reserved(pricey)).isEqualTo(1);
        assertThat(simulator.consignmentsCreated("CHEAP") + simulator.consignmentsCreated("PRICEY")).isEqualTo(1);
    }

    @Test
    void concurrentBookingAttemptsForOneShipmentCallTheCarrierOnce() throws Exception {
        carriers();
        simulator.configure("CHEAP", Mode.OK, Duration.ofMillis(100));
        CreateShipmentRequest req = mapper.readValue(shipmentJson("C-1", "PREPAID", null), CreateShipmentRequest.class);
        UUID id = bookingService.createAndAllocate(m1, req, "API", "test").shipmentId();
        List<Object> results = runConcurrently(20, i -> () -> orchestrator.book(id, "test"));
        assertThat(results).filteredOn(r -> r == BookingOrchestrator.Result.CONFIRMED).hasSize(1);
        assertThat(results).allMatch(r -> r instanceof BookingOrchestrator.Result);
        assertThat(simulator.calls("CHEAP")).isEqualTo(1);
        assertThat(simulator.consignmentsCreated("CHEAP")).isEqualTo(1);
        assertThat(orchestrator.book(id, "test")).isEqualTo(BookingOrchestrator.Result.NOTHING_TO_DO);
    }

    @Test
    void strandedAllocationIsRedrivenByReconciler() throws Exception {
        carriers();
        CreateShipmentRequest req = mapper.readValue(shipmentJson("S-1", "PREPAID", null), CreateShipmentRequest.class);
        UUID id = bookingService.createAndAllocate(m1, req, "API", "test").shipmentId(); // crash before carrier call
        assertThat(reconciler.runOnce(Duration.ZERO).redriven()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT carrier_awb FROM shipment WHERE id = ?", String.class, id)).startsWith("CHEAP-");
    }
}
