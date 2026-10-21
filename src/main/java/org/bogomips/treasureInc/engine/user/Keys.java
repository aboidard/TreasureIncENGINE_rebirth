package org.bogomips.treasureInc.engine.user;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.HexFormat;

public final class Keys {

    private static final String DIGITS = "0123456789";
    private static final String ALPHANUMERIC = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";
    // SecureRandom instead of Math.random(): these keys are credentials.
    private static final SecureRandom RANDOM = new SecureRandom();

    private Keys() {
    }

    public static String generatePublicKey(int length) {
        return random(DIGITS, length);
    }

    public static String generatePrivateKey(int length) {
        return random(ALPHANUMERIC, length);
    }

    public static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String random(String characters, int length) {
        StringBuilder sb = new StringBuilder(length);
        RANDOM.ints(length, 0, characters.length()).forEach(i -> sb.append(characters.charAt(i)));
        return sb.toString();
    }
}
