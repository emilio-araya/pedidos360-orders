package com.pedidos360.orders.catalog;

import com.pedidos360.orders.catalog.exception.CatalogServiceException;
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

    public String currentToken() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication instanceof JwtAuthenticationToken jwtAuthenticationToken) {
            return jwtAuthenticationToken.getToken().getTokenValue();
        }
        throw new CatalogServiceException("No se pudo propagar el Bearer token al catálogo");
    }
}
