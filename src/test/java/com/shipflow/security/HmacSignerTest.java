package com.shipflow.security;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class HmacSignerTest {
    private static final String SECRET = "whsec_test";
    private static final String BODY = "{\"id\":\"e1\",\"type\":\"shipment.status_changed\"}";

    @Test
    void knownVector() {
        // RFC 4231-style sanity: HMAC-SHA256("key", "The quick brown fox jumps over the lazy dog")
        assertThat(HmacSigner.hmacHex("key", "The quick brown fox jumps over the lazy dog"))
                .isEqualTo("f7bc83f430538424b13298e6aa6fb143ef4d59a14946175997479dbc2d1a3cd8");
    }

    @Test
    void verifiesOwnSignatureWithinTolerance() {
        String h = HmacSigner.signatureHeader(SECRET, 1_000, BODY);
        assertThat(h).startsWith("t=1000,v1=");
        assertThat(HmacSigner.verify(SECRET, h, BODY, 1_100, 300)).isTrue();
        assertThat(HmacSigner.verify(SECRET, h, BODY, 1_301, 300)).isFalse(); // replay window exceeded
    }

    @Test
    void rejectsTamperingAndGarbage() {
        String h = HmacSigner.signatureHeader(SECRET, 1_000, BODY);
        assertThat(HmacSigner.verify(SECRET, h, BODY + " ", 1_000, 300)).isFalse();
        assertThat(HmacSigner.verify("other", h, BODY, 1_000, 300)).isFalse();
        assertThat(HmacSigner.verify(SECRET, h.replace("t=1000", "t=1001"), BODY, 1_000, 300)).isFalse();
        assertThat(HmacSigner.verify(SECRET, null, BODY, 1_000, 300)).isFalse();
        assertThat(HmacSigner.verify(SECRET, "t=abc,v1=00", BODY, 1_000, 300)).isFalse();
        assertThat(HmacSigner.verify(SECRET, "garbage", BODY, 1_000, 300)).isFalse();
    }

    @Test
    void generatedSecretsAreUnique() {
        assertThat(HmacSigner.newSecret()).startsWith("whsec_").isNotEqualTo(HmacSigner.newSecret());
    }
}
