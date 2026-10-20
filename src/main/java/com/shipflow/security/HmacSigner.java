package com.shipflow.security;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.HexFormat;

/**
 * HMAC-SHA256 signing used for outbound merchant webhooks and to verify inbound carrier webhooks.
 * Signature scheme: {@code t=<epochSeconds>,v1=<hex(HMAC(secret, t + "." + body))>} (timestamp binds against replay).
 */
public final class HmacSigner {
    private static final SecureRandom RANDOM = new SecureRandom();

    private HmacSigner() {
    }

    public static String hmacHex(String secret, String message) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(message.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("HMAC failure", e);
        }
    }

    public static String signatureHeader(String secret, long epochSeconds, String body) {
        return "t=" + epochSeconds + ",v1=" + hmacHex(secret, epochSeconds + "." + body);
    }

    /** Constant-time verification of a {@link #signatureHeader} value with a freshness window. */
    public static boolean verify(String secret, String header, String body, long nowEpochSeconds, long toleranceSeconds) {
        if (header == null || secret == null) {
            return false;
        }
        Long t = null;
        String v1 = null;
        for (String part : header.split(",")) {
            String[] kv = part.trim().split("=", 2);
            if (kv.length != 2) {
                continue;
            }
            if (kv[0].equals("t")) {
                try {
                    t = Long.parseLong(kv[1]);
                } catch (NumberFormatException e) {
                    return false;
                }
            } else if (kv[0].equals("v1")) {
                v1 = kv[1];
            }
        }
        if (t == null || v1 == null || Math.abs(nowEpochSeconds - t) > toleranceSeconds) {
            return false;
        }
        String expected = hmacHex(secret, t + "." + body);
        return MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8), v1.getBytes(StandardCharsets.UTF_8));
    }

    public static String newSecret() {
        byte[] b = new byte[32];
        RANDOM.nextBytes(b);
        return "whsec_" + HexFormat.of().formatHex(b);
    }

    public static String sha256Hex(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
