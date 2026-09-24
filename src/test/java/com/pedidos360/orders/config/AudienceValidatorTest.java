package com.pedidos360.orders.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;

class AudienceValidatorTest {

    private final AudienceValidator validator = new AudienceValidator("pedidos360-api");

    @Test
    void acceptsRequiredAudienceAmongMultipleValues() {
        Jwt jwt = jwt(List.of("other-api", "pedidos360-api"));
        assertThat(validator.validate(jwt).hasErrors()).isFalse();
    }

    @Test
    void acceptsEquivalentEntraAudience() {
        AudienceValidator entraValidator = new AudienceValidator(
                "150f51db-4084-4979-b1a1-e6a6e7893a01"
        );
        Jwt jwt = jwt(List.of("api://150f51db-4084-4979-b1a1-e6a6e7893a01"));
        assertThat(entraValidator.validate(jwt).hasErrors()).isFalse();
    }

    @Test
    void rejectsMissingAudience() {
        assertThat(validator.validate(jwt(List.of("other-api"))).hasErrors()).isTrue();
    }

    private Jwt jwt(List<String> audience) {
        Instant now = Instant.now();
        return new Jwt(
                TOKEN,
                now,
                now.plusSeconds(60),
                Map.of("alg", "none"),
                Map.of(
                        "sub", "customer",
                        "aud", audience,
                        "iat", now.getEpochSecond()
                )
        );
    }

    private static final String TOKEN = "token";
}
