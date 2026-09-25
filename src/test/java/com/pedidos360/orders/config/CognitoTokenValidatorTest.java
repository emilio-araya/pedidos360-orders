package com.pedidos360.orders.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;

class CognitoTokenValidatorTest {

    private final CognitoAudienceValidator audienceValidator =
            new CognitoAudienceValidator("59be26pgg5ginu2sutr8eetgjg");
    private final AccessTokenValidator accessTokenValidator = new AccessTokenValidator();

    @Test
    void acceptsClientIdWhenAccessTokenHasNoAudience() {
        Jwt token = token(List.of(), "59be26pgg5ginu2sutr8eetgjg", "access");

        assertThat(audienceValidator.validate(token).hasErrors()).isFalse();
        assertThat(accessTokenValidator.validate(token).hasErrors()).isFalse();
    }

    @Test
    void rejectsAnotherClientId() {
        Jwt token = token(List.of(), "another-client", "access");

        assertThat(audienceValidator.validate(token).hasErrors()).isTrue();
    }

    @Test
    void rejectsAnotherClientEvenWhenAudienceMatches() {
        Jwt token = token(List.of("59be26pgg5ginu2sutr8eetgjg"), "another-client", "access");

        assertThat(audienceValidator.validate(token).hasErrors()).isTrue();
    }

    @Test
    void rejectsIdToken() {
        Jwt token = token(List.of(), "59be26pgg5ginu2sutr8eetgjg", "id");

        assertThat(accessTokenValidator.validate(token).hasErrors()).isTrue();
    }

    private Jwt token(List<String> audience, String clientId, String tokenUse) {
        Instant now = Instant.now();
        return Jwt.withTokenValue("token")
                .header("alg", "RS256")
                .issuedAt(now)
                .expiresAt(now.plusSeconds(300))
                .subject("cognito-sub")
                .claim("iss", "https://cognito-idp.us-east-1.amazonaws.com/us-east-1_UmEhPRYdI")
                .claim("client_id", clientId)
                .claim("token_use", tokenUse)
                .audience(audience)
                .build();
    }
}
