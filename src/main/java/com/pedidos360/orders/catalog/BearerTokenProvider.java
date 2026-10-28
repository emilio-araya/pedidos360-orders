package com.pedidos360.orders.catalog;

import java.util.Optional;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

@Component
public class BearerTokenProvider {

    public String currentApiPrefix() {
        if (RequestContextHolder.getRequestAttributes()
                instanceof ServletRequestAttributes attributes
                && attributes.getRequest().getRequestURI().startsWith("/aws/api")) {
            return "/aws/api";
        }
        return "/api";
    }

    public String currentInternalPrefix() {
        return currentApiPrefix().equals("/aws/api") ? "/aws/api/internal" : "/internal";
    }

    /**
     * Bearer of the current request, if any. Guest checkout has no JWT; the
     * catalog product read it needs is public, so the call goes without one.
     */
    public Optional<String> currentToken() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication instanceof JwtAuthenticationToken jwtAuthenticationToken) {
            return Optional.of(jwtAuthenticationToken.getToken().getTokenValue());
        }
        return Optional.empty();
    }
}
