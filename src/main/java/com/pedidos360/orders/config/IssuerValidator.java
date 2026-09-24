package com.pedidos360.orders.config;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;

public final class IssuerValidator implements OAuth2TokenValidator<Jwt> {

    private static final Pattern V2_ISSUER = Pattern.compile(
            "^https://login\\.microsoftonline\\.com/([0-9a-fA-F-]+)/v2\\.0$");

    private static final OAuth2Error INVALID_ISSUER = new OAuth2Error(
            "invalid_token",
            "The token issuer is not valid",
            null
    );

    private final Set<String> allowedIssuers;

    public IssuerValidator(String issuer) {
        if (issuer == null || issuer.isBlank()) {
            throw new IllegalArgumentException("El issuer JWT es obligatorio");
        }
        Set<String> issuers = new LinkedHashSet<>();
        issuers.add(issuer);
        Matcher matcher = V2_ISSUER.matcher(issuer);
        if (matcher.matches()) {
            issuers.add("https://sts.windows.net/" + matcher.group(1) + "/");
        }
        allowedIssuers = Set.copyOf(issuers);
    }

    @Override
    public OAuth2TokenValidatorResult validate(Jwt token) {
        if (token.getClaimAsString("iss") != null
                && allowedIssuers.contains(token.getClaimAsString("iss"))) {
            return OAuth2TokenValidatorResult.success();
        }
        return OAuth2TokenValidatorResult.failure(INVALID_ISSUER);
    }
}
