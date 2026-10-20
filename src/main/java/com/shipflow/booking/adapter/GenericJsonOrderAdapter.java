package com.shipflow.booking.adapter;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shipflow.booking.api.CreateShipmentRequest;
import com.shipflow.common.error.ErrorCode;
import com.shipflow.common.error.ShipFlowException;
import org.springframework.stereotype.Component;

/** Generic adapter: payload already is a ShipFlow CreateShipmentRequest (originHubCode may come from the query). */
@Component
public class GenericJsonOrderAdapter implements OrderAdapter {
    private final ObjectMapper mapper;

    public GenericJsonOrderAdapter(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    public String source() {
        return "generic";
    }

    public String externalOrderId(JsonNode p) {
        String ref = p.path("merchantOrderRef").asText();
        if (ref.isBlank()) {
            throw new ShipFlowException(ErrorCode.VALIDATION_FAILED, "merchantOrderRef is required");
        }
        return "generic-" + ref;
    }

    public CreateShipmentRequest toShipmentRequest(JsonNode p, String originHubCode) {
        try {
            CreateShipmentRequest r = mapper.treeToValue(p, CreateShipmentRequest.class);
            if (r.originHubCode() == null && originHubCode != null) {
                r = new CreateShipmentRequest(r.merchantOrderRef(), originHubCode, r.consignee(), r.weightGrams(),
                        r.paymentMode(), r.codAmount(), r.declaredValue());
            }
            return r;
        } catch (Exception e) {
            throw new ShipFlowException(ErrorCode.VALIDATION_FAILED, "invalid generic order payload: " + e.getMessage());
        }
    }
}
