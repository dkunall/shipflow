package com.shipflow.booking.adapter;

import com.fasterxml.jackson.databind.JsonNode;
import com.shipflow.booking.api.ConsigneeDto;
import com.shipflow.booking.api.CreateShipmentRequest;
import com.shipflow.booking.api.MoneyDto;
import com.shipflow.common.error.ErrorCode;
import com.shipflow.common.error.ShipFlowException;
import com.shipflow.shipment.domain.PaymentMode;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.Locale;

/**
 * Maps the subset of a Shopify "orders/create" webhook we need: id, name, currency, total_price,
 * financial_status + payment_gateway_names (COD detection), total_weight (grams), shipping_address.
 * Destination area code is taken from shipping_address.zip.
 */
@Component
public class ShopifyOrderAdapter implements OrderAdapter {
    public String source() {
        return "shopify";
    }

    public String externalOrderId(JsonNode p) {
        return "shopify-" + required(p, "id");
    }

    public CreateShipmentRequest toShipmentRequest(JsonNode p, String originHubCode) {
        JsonNode addr = p.path("shipping_address");
        if (addr.isMissingNode() || addr.isNull()) {
            throw new ShipFlowException(ErrorCode.VALIDATION_FAILED, "shopify order has no shipping_address");
        }
        String currency = required(p, "currency");
        BigDecimal total = new BigDecimal(required(p, "total_price"));
        boolean cod = isCod(p);
        Integer grams = p.path("total_weight").isNumber() ? p.path("total_weight").asInt() : null;
        String name = (text(addr, "first_name") + " " + text(addr, "last_name")).trim();
        if (name.isEmpty()) {
            name = text(addr, "name");
        }
        return new CreateShipmentRequest("shopify-" + required(p, "id"), originHubCode,
                new ConsigneeDto(name, text(addr, "phone").replace(" ", ""), text(addr, "address1"),
                        nullIfBlank(text(addr, "address2")), nullIfBlank(text(addr, "company")),
                        text(addr, "city"), text(addr, "zip"),
                        addr.path("latitude").isNumber() ? addr.path("latitude").asDouble() : null,
                        addr.path("longitude").isNumber() ? addr.path("longitude").asDouble() : null),
                grams == null || grams <= 0 ? 500 : grams,
                cod ? PaymentMode.COD : PaymentMode.PREPAID,
                cod ? new MoneyDto(total, currency) : null,
                new MoneyDto(total, currency));
    }

    static boolean isCod(JsonNode p) {
        if (!"pending".equalsIgnoreCase(p.path("financial_status").asText())) {
            return false;
        }
        for (JsonNode g : p.path("payment_gateway_names")) {
            String s = g.asText().toLowerCase(Locale.ROOT);
            if (s.contains("cash on delivery") || s.contains("cod")) {
                return true;
            }
        }
        return false;
    }

    private static String required(JsonNode p, String field) {
        JsonNode n = p.path(field);
        if (n.isMissingNode() || n.isNull() || n.asText().isBlank()) {
            throw new ShipFlowException(ErrorCode.VALIDATION_FAILED, "shopify order missing '" + field + "'");
        }
        return n.asText();
    }

    private static String text(JsonNode n, String field) {
        JsonNode v = n.path(field);
        return v.isMissingNode() || v.isNull() ? "" : v.asText();
    }

    private static String nullIfBlank(String s) {
        return s == null || s.isBlank() ? null : s;
    }
}
