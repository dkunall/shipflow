package com.shipflow.booking.adapter;

import com.shipflow.common.error.ErrorCode;
import com.shipflow.common.error.ShipFlowException;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

@Component
public class OrderAdapterRegistry {
    private final Map<String, OrderAdapter> adapters;

    public OrderAdapterRegistry(List<OrderAdapter> adapters) {
        this.adapters = adapters.stream().collect(Collectors.toUnmodifiableMap(OrderAdapter::source, Function.identity()));
    }

    public OrderAdapter get(String source) {
        OrderAdapter a = adapters.get(source);
        if (a == null) {
            throw new ShipFlowException(ErrorCode.UNSUPPORTED_SOURCE, "unsupported order source '" + source
                    + "'; supported: " + adapters.keySet().stream().sorted().toList());
        }
        return a;
    }

    public Set<String> sources() {
        return adapters.keySet();
    }
}
