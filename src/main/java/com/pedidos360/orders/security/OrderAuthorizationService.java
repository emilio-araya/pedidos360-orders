package com.pedidos360.orders.security;

import com.pedidos360.orders.order.domain.Order;
import com.pedidos360.orders.order.domain.OrderStatus;
import com.pedidos360.orders.order.exception.ForbiddenOperationException;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;

@Component
public class OrderAuthorizationService {

    public boolean isStaff(Authentication authentication) {
        return hasRole(authentication, "Admin") || hasRole(authentication, "Operador");
    }

    public void requireKnownRole(Authentication authentication) {
        if (!hasRole(authentication, "Admin")
                && !hasRole(authentication, "Operador")
                && !hasRole(authentication, "Cliente")) {
            throw new ForbiddenOperationException("El token no contiene un rol de Pedidos360");
        }
    }

    public void requireView(Order order, Authentication authentication) {
        requireKnownRole(authentication);
        if (isStaff(authentication)) {
            return;
        }
        requireOwner(order, authentication);
    }

    public void requireEdit(Order order, Authentication authentication) {
        requireKnownRole(authentication);
        if (isStaff(authentication)) {
            return;
        }
        requireOwner(order, authentication);
    }

    public void requireStatusChange(
            Order order,
            OrderStatus target,
            Authentication authentication
    ) {
        requireKnownRole(authentication);
        if (target == OrderStatus.CANCELADO && !isStaff(authentication)) {
            if (order.getStatus() != OrderStatus.CREADO) {
                throw new ForbiddenOperationException(
                        "Un cliente solo puede cancelar sus pedidos mientras están en CREADO"
                );
            }
            requireOwner(order, authentication);
            return;
        }
        if (!isStaff(authentication)) {
            throw new ForbiddenOperationException("La transición requiere rol Admin u Operador");
        }
    }

    public void requireDeleteCancellation(Order order, Authentication authentication) {
        requireKnownRole(authentication);
        if (order.getStatus() != OrderStatus.CREADO) {
            throw new ForbiddenOperationException("DELETE solo cancela pedidos en estado CREADO");
        }
        if (isStaff(authentication)) {
            return;
        }
        requireOwner(order, authentication);
    }

    private void requireOwner(Order order, Authentication authentication) {
        OrderPrincipal principal = OrderPrincipal.from(authentication);
        if (!order.getCustomerId().equals(principal.customerId())) {
            throw new ForbiddenOperationException("Solo puede acceder a sus propios pedidos");
        }
    }

    private boolean hasRole(Authentication authentication, String expectedRole) {
        if (authentication == null) {
            return false;
        }
        String authority = "ROLE_" + expectedRole;
        return authentication.getAuthorities().stream()
                .anyMatch(granted -> authority.equals(granted.getAuthority()));
    }
}
