package com.pedidos360.orders.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;

class LocalJwtDecoderTest {

    private static final String SECRET = "unit-test-local-hmac-secret-at-least-32-bytes";
    private static final String AUDIENCE = "pedidos360-api";

    private final JwtDecoder decoder = new SecurityConfiguration().localJwtDecoder(
            new AppSecurityProperties("local-issuer", AUDIENCE, SECRET)
    );

    @Test
    void acceptsValidHmacTokenWithRequiredAudience() throws Exception {
        Jwt jwt = decoder.decode(token(SECRET, AUDIENCE, Instant.now().plusSeconds(300), null));
        assertThat(jwt.getSubject()).isEqualTo("customer-1");
        assertThat(jwt.getAudience()).contains(AUDIENCE);
    }

    @Test
    void rejectsInvalidSignature() throws Exception {
        String token = token("a-different-local-secret-that-is-at-least-32-bytes", AUDIENCE,
                Instant.now().plusSeconds(300), null);

        assertThatThrownBy(() -> decoder.decode(token))
                .isInstanceOf(org.springframework.security.oauth2.jwt.JwtException.class);
    }

    @Test
    void rejectsWrongAudienceAndExpiredOrFutureNotBeforeTokens() throws Exception {
        String wrongAudience = token(SECRET, "other-api", Instant.now().plusSeconds(300), null);
        String expired = token(SECRET, AUDIENCE, Instant.now().minusSeconds(120), null);
        String futureNotBefore = token(
                SECRET,
                AUDIENCE,
                Instant.now().plusSeconds(300),
                Instant.now().plusSeconds(120)
        );

        assertThatThrownBy(() -> decoder.decode(wrongAudience)).hasMessageContaining("audiencia");
        assertThatThrownBy(() -> decoder.decode(expired)).hasMessageContaining("exp");
        assertThatThrownBy(() -> decoder.decode(futureNotBefore)).hasMessageContaining("used before");
    }

    private String token(
            String secret,
            String audience,
            Instant expiration,
            Instant notBefore
    ) throws Exception {
        Instant now = Instant.now();
        JWTClaimsSet.Builder claims = new JWTClaimsSet.Builder()
                .subject("customer-1")
                .issuer("local-issuer")
                .audience(audience)
                .issueTime(Date.from(now.minusSeconds(5)))
                .expirationTime(Date.from(expiration));
        if (notBefore != null) {
            claims.notBeforeTime(Date.from(notBefore));
        }
        SignedJWT jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), claims.build());
        jwt.sign(new MACSigner(secret.getBytes(StandardCharsets.UTF_8)));
        return jwt.serialize();
    }
}
