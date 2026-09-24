package com.pedidos360.orders.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;

class IssuerValidatorTest {

    private final IssuerValidator validator = new IssuerValidator(
            "https://login.microsoftonline.com/1feca74f-8331-414a-bd8d-2d687b22a7b3/v2.0"
    );

    @Test
    void acceptsTenantV1Issuer() {
        assertThat(validator.validate(jwt(
                "https://sts.windows.net/1feca74f-8331-414a-bd8d-2d687b22a7b3/"
        )).hasErrors()).isFalse();
    }

    @Test
    void rejectsAnotherTenantIssuer() {
        assertThat(validator.validate(jwt(
                "https://sts.windows.net/00000000-0000-0000-0000-000000000000/"
        )).hasErrors()).isTrue();
    }

    private Jwt jwt(String issuer) {
        Instant now = Instant.now();
        return new Jwt(
                "token",
                now,
                now.plusSeconds(300),
                Map.of("alg", "RS256"),
                Map.of(
                        "iss", issuer,
                        "aud", List.of("api://150f51db-4084-4979-b1a1-e6a6e7893a01"),
                        "sub", "user"
                )
        );
    }
}
