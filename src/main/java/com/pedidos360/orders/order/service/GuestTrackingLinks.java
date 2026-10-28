package com.pedidos360.orders.order.service;

import java.util.Locale;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Builds the public tracking link that travels in the voucher email. */
@Component
public class GuestTrackingLinks {

    private final String frontendBaseUrl;

    public GuestTrackingLinks(@Value("${app.guest.mail.frontend-public-url}") String frontendBaseUrl) {
        this.frontendBaseUrl = stripTrailingSlash(frontendBaseUrl);
    }

    public String linkFor(String token) {
        return frontendBaseUrl + "/seguimiento/" + token;
    }

    private static String stripTrailingSlash(String value) {
        String trimmed = value == null ? "" : value.trim();
        while (trimmed.endsWith("/")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        return trimmed.toLowerCase(Locale.ROOT).startsWith("http")
                ? trimmed
                : "http://localhost:4200";
    }
}
