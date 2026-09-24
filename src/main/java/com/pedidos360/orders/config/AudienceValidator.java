package com.pedidos360.orders.config;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;

public final class AudienceValidator implements OAuth2TokenValidator<Jwt> {

    private static final String ERROR_CODE = "invalid_token";

    private static final Pattern GUID = Pattern.compile(
            "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$");

    private final Set<String> allowedAudiences;

    public AudienceValidator(String requiredAudience) {
        if (requiredAudience == null || requiredAudience.isBlank()) {
            throw new IllegalArgumentException("La audiencia JWT es obligatoria");
        }
        Set<String> audiences = new LinkedHashSet<>();
        audiences.add(requiredAudience);
        if (GUID.matcher(requiredAudience).matches()) {
            audiences.add("api://" + requiredAudience);
        } else if (requiredAudience.startsWith("api://")
                && GUID.matcher(requiredAudience.substring("api://".length())).matches()) {
            audiences.add(requiredAudience.substring("api://".length()));
        }
        allowedAudiences = Set.copyOf(audiences);
    }

    @Override
    public OAuth2TokenValidatorResult validate(Jwt token) {
        if (token.getAudience().stream().anyMatch(allowedAudiences::contains)) {
            return OAuth2TokenValidatorResult.success();
        }
        return OAuth2TokenValidatorResult.failure(new OAuth2Error(
                ERROR_CODE,
                "El token no contiene una audiencia requerida",
                null
        ));
    }
}
