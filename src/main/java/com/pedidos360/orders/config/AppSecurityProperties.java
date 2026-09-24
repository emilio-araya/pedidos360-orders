package com.pedidos360.orders.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.security")
public record AppSecurityProperties(
        String issuer,
        String audience,
        String hmacSecret
) {
}
