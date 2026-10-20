package com.shipflow.support;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.UUID;

/** Mints real HS256 tokens so tests exercise the actual JwtDecoder + role/merchant mapping. */
public final class TestJwt {
    public static final String SECRET = "test-only-hmac-secret-0123456789-abcdefghijklmnop";

    private TestJwt() {
    }

    public static String token(String secret, String subject, UUID merchantId, Instant exp, String... roles) {
        try {
            JWTClaimsSet.Builder b = new JWTClaimsSet.Builder().subject(subject).claim("roles", List.of(roles))
                    .issueTime(new Date()).expirationTime(Date.from(exp));
            if (merchantId != null) {
                b.claim("merchant_id", merchantId.toString());
            }
            SignedJWT jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), b.build());
            jwt.sign(new MACSigner(secret.getBytes(StandardCharsets.UTF_8)));
            return jwt.serialize();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    public static String merchant(UUID merchantId) {
        return "Bearer " + token(SECRET, "merchant-user-" + merchantId, merchantId, Instant.now().plusSeconds(3600), "MERCHANT");
    }

    public static String ops() {
        return "Bearer " + token(SECRET, "ops-1", null, Instant.now().plusSeconds(3600), "OPS");
    }

    public static String admin() {
        return "Bearer " + token(SECRET, "admin-1", null, Instant.now().plusSeconds(3600), "ADMIN");
    }

    public static String rider() {
        return "Bearer " + token(SECRET, "rider-1", null, Instant.now().plusSeconds(3600), "RIDER");
    }
}
