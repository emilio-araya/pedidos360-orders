package com.pedidos360.orders.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.security.cognito")
public record CognitoSecurityProperties(
        String issuer,
        String audience,
        String jwkSetUri
) {
    public boolean isConfigured() {
        return issuer != null && !issuer.isBlank()
                && audience != null && !audience.isBlank();
    }
}
