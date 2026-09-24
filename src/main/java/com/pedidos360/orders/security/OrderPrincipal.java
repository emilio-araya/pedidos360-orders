package com.pedidos360.orders.security;

import com.pedidos360.orders.order.exception.ForbiddenOperationException;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.jwt.Jwt;

public record OrderPrincipal(String customerId) {

    public static OrderPrincipal from(Authentication authentication) {
        if (authentication == null || !(authentication.getPrincipal() instanceof Jwt jwt)) {
            throw new ForbiddenOperationException("La identidad JWT no es válida");
        }
        String oid = jwt.getClaimAsString("oid");
        String subject = jwt.getClaimAsString("sub");
        String customerId = hasText(oid) ? oid : subject;
        if (!hasText(customerId)) {
            throw new ForbiddenOperationException("El token debe contener el claim oid o sub");
        }
        if (customerId.length() > 255) {
            throw new ForbiddenOperationException("El claim oid o sub supera la longitud permitida");
        }
        return new OrderPrincipal(customerId);
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
