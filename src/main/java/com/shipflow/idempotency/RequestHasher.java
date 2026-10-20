package com.shipflow.idempotency;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.shipflow.security.HmacSigner;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** SHA-256 over a canonical JSON form (keys sorted recursively) so semantically equal payloads hash equal. */
@Component
public class RequestHasher {
    private final ObjectMapper canonical = JsonMapper.builder()
            .addModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
            .build();

    public String hash(Object request) {
        try {
            JsonNode tree = canonical.valueToTree(request);
            return HmacSigner.sha256Hex(canonical.writeValueAsString(sort(tree)));
        } catch (Exception e) {
            throw new IllegalStateException("cannot hash request", e);
        }
    }

    private JsonNode sort(JsonNode node) {
        if (node.isObject()) {
            ObjectNode sorted = canonical.createObjectNode();
            List<String> names = new ArrayList<>();
            node.fieldNames().forEachRemaining(names::add);
            Collections.sort(names);
            for (String n : names) {
                JsonNode child = node.get(n);
                if (!child.isNull()) {
                    sorted.set(n, sort(child));
                }
            }
            return sorted;
        }
        if (node.isArray()) {
            var arr = canonical.createArrayNode();
            node.forEach(c -> arr.add(sort(c)));
            return arr;
        }
        return node;
    }
}
