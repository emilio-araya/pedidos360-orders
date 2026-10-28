package com.pedidos360.orders.order.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * Issues and hashes guest tracking tokens. A token is 256 random bits as 64
 * lowercase hex characters; only its SHA-256 is persisted, so a database leak
 * does not expose working links.
 */
@Component
public class GuestTrackingTokens {

    private static final int TOKEN_BYTES = 32;
    private static final Pattern WELL_FORMED = Pattern.compile("^[0-9a-f]{64}$");
    private static final HexFormat HEX = HexFormat.of();

    private final SecureRandom random = new SecureRandom();

    public String newToken() {
        byte[] bytes = new byte[TOKEN_BYTES];
        random.nextBytes(bytes);
        return HEX.formatHex(bytes);
    }

    public boolean isWellFormed(String token) {
        return token != null && WELL_FORMED.matcher(token).matches();
    }

    public String hash(String token) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(token.getBytes(StandardCharsets.US_ASCII));
            return HEX.formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 no está disponible en la JVM", exception);
        }
    }
}
