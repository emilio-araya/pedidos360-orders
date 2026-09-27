package com.pedidos360.orders.config;

import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;

public final class AccessTokenValidator implements OAuth2TokenValidator<Jwt> {

    private static final OAuth2Error INVALID_TOKEN_USE = new OAuth2Error(
            "invalid_token",
            "A Cognito access token is required",
            null
    );

    @Override
    public OAuth2TokenValidatorResult validate(Jwt token) {
        if ("access".equals(token.getClaimAsString("token_use"))) {
            return OAuth2TokenValidatorResult.success();
        }
        return OAuth2TokenValidatorResult.failure(INVALID_TOKEN_USE);
    }
}
