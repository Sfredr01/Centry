package com.centry.security;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;
import java.util.regex.Pattern;

/**
 * Token format and hashing. A token is {@code centry_} plus 32 random bytes in unpadded base64url (43 chars);
 * the prefix lets secret scanners and people recognise it. Only the SHA-256 hex is stored: the input has 256 bits
 * of entropy, so a fast hash is enough and a slow one would only cost a Pi time on every request.
 */
public final class ApiTokens {

    static final String PREFIX = "centry_";

    private static final Pattern FORMAT = Pattern.compile("centry_[A-Za-z0-9_-]{43}");
    private static final SecureRandom RANDOM = new SecureRandom();

    private ApiTokens() {
    }

    public static String generate() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    static boolean isWellFormed(String token) {
        return FORMAT.matcher(token).matches();
    }

    public static String hash(String token) {
        return digest("SHA-256", token);
    }

    // The algorithm is a parameter only so a test can reach the failure branch; production always passes SHA-256.
    static String digest(String algorithm, String token) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance(algorithm).digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            // Every Java platform must provide SHA-256, so this means a programming error.
            throw new IllegalStateException(e);
        }
    }
}
