package com.shipflow.shipment;

import com.shipflow.shipment.domain.ShipmentStateMachine;
import com.shipflow.shipment.domain.ShipmentStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.List;

import static com.shipflow.shipment.domain.ShipmentStatus.*;
import static org.assertj.core.api.Assertions.assertThat;

class ShipmentStateMachineTest {

    @ParameterizedTest
    @CsvSource({"CREATED,ALLOCATED", "ALLOCATED,PICKED_UP", "PICKED_UP,IN_TRANSIT", "IN_TRANSIT,AT_HUB",
            "AT_HUB,OUT_FOR_DELIVERY", "OUT_FOR_DELIVERY,DELIVERED", "OUT_FOR_DELIVERY,DELIVERY_FAILED",
            "DELIVERY_FAILED,OUT_FOR_DELIVERY", "DELIVERY_FAILED,RTO_INITIATED", "RTO_INITIATED,RTO_IN_TRANSIT",
            "RTO_IN_TRANSIT,RTO_DELIVERED", "CREATED,CANCELLED", "ALLOCATED,CANCELLED"})
    void allowsSpecifiedTransitions(ShipmentStatus from, ShipmentStatus to) {
        assertThat(ShipmentStateMachine.canTransition(from, to)).isTrue();
    }

    @ParameterizedTest
    @CsvSource({"CREATED,DELIVERED", "DELIVERED,OUT_FOR_DELIVERY", "IN_TRANSIT,CANCELLED", "PICKED_UP,CANCELLED",
            "RTO_INITIATED,OUT_FOR_DELIVERY", "OUT_FOR_DELIVERY,RTO_INITIATED", "CANCELLED,CREATED",
            "RTO_DELIVERED,DELIVERED", "OUT_FOR_DELIVERY,PICKED_UP"})
    void rejectsEverythingElse(ShipmentStatus from, ShipmentStatus to) {
        assertThat(ShipmentStateMachine.canTransition(from, to)).isFalse();
    }

    @ParameterizedTest
    @EnumSource(value = ShipmentStatus.class, names = {"DELIVERED", "CANCELLED", "RTO_DELIVERED"})
    void terminalStatesHaveNoExits(ShipmentStatus s) {
        assertThat(s.isTerminal()).isTrue();
        assertThat(ShipmentStateMachine.allowedFrom(s)).isEmpty();
    }

    @Test
    void forwardTransitionsNeverDecreaseRankExceptExplicitLoops() {
        // invariant used by out-of-order handling: only the documented loops go "down" or sideways
        for (ShipmentStatus from : ShipmentStatus.values()) {
            for (ShipmentStatus to : ShipmentStateMachine.allowedFrom(from)) {
                boolean explicitLoop = (from == DELIVERY_FAILED && to == OUT_FOR_DELIVERY)
                        || (from == ALLOCATED && to == CREATED) || (from == AT_HUB && to == IN_TRANSIT)
                        || (from == IN_TRANSIT && to == AT_HUB);
                if (!explicitLoop) {
                    assertThat(to.rank()).as("%s -> %s", from, to).isGreaterThanOrEqualTo(from.rank());
                }
            }
        }
    }

    @Test
    void scanPathInfersSkippedForwardSteps() {
        assertThat(ShipmentStateMachine.scanPath(ALLOCATED, OUT_FOR_DELIVERY)).hasValueSatisfying(p -> {
            assertThat(p.get(p.size() - 1)).isEqualTo(OUT_FOR_DELIVERY);
            assertThat(p.get(0)).isEqualTo(PICKED_UP);
        });
        assertThat(ShipmentStateMachine.scanPath(ALLOCATED, DELIVERED).orElseThrow())
                .endsWith(OUT_FOR_DELIVERY, DELIVERED);
        assertThat(ShipmentStateMachine.scanPath(DELIVERY_FAILED, OUT_FOR_DELIVERY)).contains(List.of(OUT_FOR_DELIVERY));
    }

    @Test
    void scanPathNeverInfersFailuresRtoOrCancellation() {
        // DELIVERED from DELIVERY_FAILED requires a new OFD first, inferred
        assertThat(ShipmentStateMachine.scanPath(DELIVERY_FAILED, DELIVERED)).contains(List.of(OUT_FOR_DELIVERY, DELIVERED));
        // RTO_IN_TRANSIT is not reachable by scans before an RTO decision
        assertThat(ShipmentStateMachine.scanPath(OUT_FOR_DELIVERY, RTO_IN_TRANSIT)).isEmpty();
        assertThat(ShipmentStateMachine.scanPath(ALLOCATED, CANCELLED)).isEmpty();
        assertThat(ShipmentStateMachine.scanPath(DELIVERED, OUT_FOR_DELIVERY)).isEmpty();
        assertThat(ShipmentStateMachine.isScanTarget(CREATED)).isFalse();
        // carriers may report RTO initiation themselves, but only after a failed attempt
        assertThat(ShipmentStateMachine.scanPath(DELIVERY_FAILED, RTO_INITIATED)).contains(List.of(RTO_INITIATED));
        assertThat(ShipmentStateMachine.scanPath(OUT_FOR_DELIVERY, RTO_INITIATED)).isEmpty();
    }
}
