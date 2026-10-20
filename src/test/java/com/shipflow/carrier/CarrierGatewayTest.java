package com.shipflow.carrier;

import com.shipflow.carrier.domain.CarrierInfo;
import com.shipflow.carrier.domain.CarrierKind;
import com.shipflow.carrier.port.BookingRequest;
import com.shipflow.carrier.port.BookingResult;
import com.shipflow.carrier.port.CarrierPort;
import com.shipflow.carrier.port.CarrierPortRegistry;
import com.shipflow.carrier.port.CarrierRejectedException;
import com.shipflow.carrier.resilience.CarrierGateway;
import com.shipflow.carrier.resilience.CarrierGateway.CallOutcome;
import com.shipflow.carrier.resilience.CircuitBreakerRegistry;
import com.shipflow.config.ShipFlowProperties;
import com.shipflow.support.TestProps;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CarrierGatewayTest {
    private final CarrierPort port = mock(CarrierPort.class);
    private final CarrierInfo carrier = new CarrierInfo(1, "X", "X", CarrierKind.THIRD_PARTY, "FAKE", true, "s");
    private final BookingRequest req = new BookingRequest("X", "SF-1-X", "SF1", "HUB", "P", 500, false, null, "AED");
    private final CarrierGateway gateway;
    private final CircuitBreakerRegistry breakers;

    CarrierGatewayTest() {
        when(port.integration()).thenReturn("FAKE");
        ShipFlowProperties d = TestProps.defaults();
        ShipFlowProperties props = new ShipFlowProperties(d.jwt(), d.scheduling(), d.idempotency(), d.allocation(),
                d.scorecard(), new ShipFlowProperties.Booking(Duration.ofMillis(200), Duration.ofSeconds(30),
                Duration.ofSeconds(15), Duration.ofMinutes(1), 50), d.circuitBreaker(), d.ndr(), d.risk(), d.edd(),
                d.webhook(), d.cod(), d.routing());
        breakers = new CircuitBreakerRegistry(props, Clock.systemUTC(), new SimpleMeterRegistry());
        gateway = new CarrierGateway(new CarrierPortRegistry(List.of(port)), breakers, props);
    }

    @Test
    void mapsSuccessRejectionFailureAndTimeout() throws Exception {
        when(port.book(any())).thenReturn(new BookingResult("AWB1"));
        assertThat(gateway.book(carrier, req)).isEqualTo(new CallOutcome.Booked("AWB1"));

        org.mockito.Mockito.doThrow(new CarrierRejectedException("pincode blocked")).when(port).book(any());
        assertThat(gateway.book(carrier, req)).isInstanceOfSatisfying(CallOutcome.Failed.class,
                f -> assertThat(f.businessRejection()).isTrue());

        org.mockito.Mockito.doThrow(new IllegalStateException("503")).when(port).book(any());
        assertThat(gateway.book(carrier, req)).isInstanceOfSatisfying(CallOutcome.Failed.class,
                f -> assertThat(f.businessRejection()).isFalse());

        org.mockito.Mockito.doAnswer(inv -> {
            Thread.sleep(2000);
            return new BookingResult("late");
        }).when(port).book(any());
        long t0 = System.nanoTime();
        assertThat(gateway.book(carrier, req)).isInstanceOf(CallOutcome.Uncertain.class);
        assertThat((System.nanoTime() - t0) / 1_000_000).isLessThan(1500);
    }

    @Test
    void openCircuitShortCircuitsWithoutCallingCarrier() throws Exception {
        when(port.book(any())).thenThrow(new IllegalStateException("down"));
        for (int i = 0; i < 5; i++) {
            gateway.book(carrier, req);
        }
        org.mockito.Mockito.clearInvocations(port);
        assertThat(gateway.book(carrier, req)).isInstanceOf(CallOutcome.CircuitOpen.class);
        verify(port, never()).book(any());
    }

    @Test
    void rejectionsDoNotTripTheBreaker() throws Exception {
        when(port.book(any())).thenThrow(new CarrierRejectedException("no"));
        for (int i = 0; i < 10; i++) {
            gateway.book(carrier, req);
        }
        assertThat(breakers.forCarrier("X").state().name()).isEqualTo("CLOSED");
    }

    @Test
    void lookupDistinguishesNotFoundFromUnknown() throws Exception {
        when(port.lookup("X", "R1")).thenReturn(Optional.of(new BookingResult("A")));
        when(port.lookup("X", "R2")).thenReturn(Optional.empty());
        when(port.lookup("X", "R3")).thenThrow(new IllegalStateException("down"));
        assertThat(gateway.lookup(carrier, "R1")).contains(Optional.of("A"));
        assertThat(gateway.lookup(carrier, "R2")).contains(Optional.empty());
        assertThat(gateway.lookup(carrier, "R3")).isEmpty();
    }

    @Test
    void refusesToCallCarrierInsideTransaction() {
        TransactionSynchronizationManager.setActualTransactionActive(true);
        try {
            assertThatThrownBy(() -> gateway.book(carrier, req)).isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("transaction");
        } finally {
            TransactionSynchronizationManager.setActualTransactionActive(false);
        }
    }
}
