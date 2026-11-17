package com.ruangpay.util;

import javax.crypto.SecretKeyFactory;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import javax.crypto.spec.PBEKeySpec;
import java.security.SecureRandom;
import java.util.Base64;

public final class Security {
    private static final SecureRandom RANDOM = new SecureRandom();
    private Security() {}

    public static String token(String prefix) {
        byte[] bytes = new byte[24];
        RANDOM.nextBytes(bytes);
        return prefix + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    public static String hashPassword(String password) {
        try {
            byte[] salt = new byte[16];
            RANDOM.nextBytes(salt);
            PBEKeySpec spec = new PBEKeySpec(password.toCharArray(), salt, 120_000, 256);
            byte[] hash = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
                    .generateSecret(spec).getEncoded();
            return Base64.getEncoder().encodeToString(salt) + ":" +
                    Base64.getEncoder().encodeToString(hash);
        } catch (Exception e) { throw new IllegalStateException(e); }
    }

    public static boolean verifyPassword(String password, String encoded) {
        try {
            String[] parts = encoded.split(":");
            byte[] salt = Base64.getDecoder().decode(parts[0]);
            byte[] expected = Base64.getDecoder().decode(parts[1]);
            PBEKeySpec spec = new PBEKeySpec(password.toCharArray(), salt, 120_000, 256);
            byte[] actual = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
                    .generateSecret(spec).getEncoded();
            return java.security.MessageDigest.isEqual(expected, actual);
        } catch (Exception e) { return false; }
    }

    public static String hmacSha256(String secret, String payload) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(java.nio.charset.StandardCharsets.UTF_8), "HmacSHA256"));
            return java.util.HexFormat.of().formatHex(
                    mac.doFinal(payload.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (Exception e) { throw new IllegalStateException(e); }
    }

    public static boolean secureEquals(String expected, String actual) {
        if (expected == null || actual == null) return false;
        return java.security.MessageDigest.isEqual(
                expected.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                actual.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }
}
