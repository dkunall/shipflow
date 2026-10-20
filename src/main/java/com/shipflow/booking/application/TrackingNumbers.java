package com.shipflow.booking.application;

import java.security.SecureRandom;

/** "SF" + 12 Crockford base32 chars (60 random bits): unguessable enough for a public tracking page. */
public final class TrackingNumbers {
    private static final char[] ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ".toCharArray();
    private static final SecureRandom RANDOM = new SecureRandom();

    private TrackingNumbers() {
    }

    public static String next() {
        long bits = RANDOM.nextLong();
        char[] out = new char[14];
        out[0] = 'S';
        out[1] = 'F';
        for (int i = 0; i < 12; i++) {
            out[2 + i] = ALPHABET[(int) (bits & 31)];
            bits >>>= 5;
        }
        return new String(out);
    }
}
