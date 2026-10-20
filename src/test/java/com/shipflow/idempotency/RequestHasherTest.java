package com.shipflow.idempotency;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class RequestHasherTest {
    private final RequestHasher hasher = new RequestHasher();

    @Test
    void keyOrderDoesNotMatterButValuesDo() {
        Map<String, Object> a = new LinkedHashMap<>();
        a.put("x", 1);
        a.put("nested", Map.of("b", 2, "a", 1));
        Map<String, Object> b = new LinkedHashMap<>();
        b.put("nested", Map.of("a", 1, "b", 2));
        b.put("x", 1);
        assertThat(hasher.hash(a)).isEqualTo(hasher.hash(b)).hasSize(64);
        b.put("x", 2);
        assertThat(hasher.hash(a)).isNotEqualTo(hasher.hash(b));
        assertThat(hasher.hash(List.of(1, 2))).isNotEqualTo(hasher.hash(List.of(2, 1))); // arrays are ordered
    }
}
