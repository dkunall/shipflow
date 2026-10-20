package com.shipflow.carrier.port;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/** Resolves the adapter for a carrier's integration type. Adding a carrier API = one new CarrierPort bean. */
@Component
public class CarrierPortRegistry {
    private final Map<String, CarrierPort> ports;

    public CarrierPortRegistry(List<CarrierPort> ports) {
        this.ports = ports.stream().collect(Collectors.toUnmodifiableMap(CarrierPort::integration, Function.identity()));
    }

    public CarrierPort forIntegration(String integration) {
        CarrierPort p = ports.get(integration);
        if (p == null) {
            throw new IllegalStateException("no CarrierPort for integration " + integration);
        }
        return p;
    }

    public boolean supports(String integration) {
        return ports.containsKey(integration);
    }
}
