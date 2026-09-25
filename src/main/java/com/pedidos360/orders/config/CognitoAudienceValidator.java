package com.pedidos360.orders.config;

import java.util.List;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;

public final class CognitoAudienceValidator implements OAuth2TokenValidator<Jwt> {

    private static final OAuth2Error INVALID_AUDIENCE = new OAuth2Error(
            "invalid_token",
            "The token does not contain the required Cognito audience",
            null
    );

    private final String audience;

    public CognitoAudienceValidator(String audience) {
        if (audience == null || audience.isBlank()) {
            throw new IllegalArgumentException("La audiencia Cognito es obligatoria");
        }
        this.audience = audience;
    }

    @Override
    public OAuth2TokenValidatorResult validate(Jwt token) {
        String clientId = token.getClaimAsString("client_id");
        if (clientId != null && !audience.equals(clientId)) {
            return OAuth2TokenValidatorResult.failure(INVALID_AUDIENCE);
        }

        // Jwt.getAudience() devuelve null cuando el token no trae la claim `aud`,
        // no una lista vacía. Un App Client publico sin servidor de recursos
        // recibe un access token con `client_id` y `scope`, y sin `aud`, por lo
        // que hay que tratar ambos casos.
        List<String> audiences = token.getAudience();

        if (audiences != null && audiences.contains(audience)) {
            return OAuth2TokenValidatorResult.success();
        }
        if ((audiences == null || audiences.isEmpty()) && audience.equals(clientId)) {
            return OAuth2TokenValidatorResult.success();
        }
        return OAuth2TokenValidatorResult.failure(INVALID_AUDIENCE);
    }
}
