package com.pedidos360.orders.order.domain;

import com.pedidos360.orders.order.exception.InvalidOrderTransitionException;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;

@Component
public class OrderTransitionPolicy {

    private static final Map<OrderStatus, Set<OrderStatus>> ALLOWED_TRANSITIONS = allowedTransitions();

    public void requireTransition(OrderStatus current, OrderStatus target) {
        Set<OrderStatus> allowed = ALLOWED_TRANSITIONS.getOrDefault(current, Set.of());
        if (!allowed.contains(target)) {
            throw new InvalidOrderTransitionException(current, target);
        }
    }

    public boolean canTransition(OrderStatus current, OrderStatus target) {
        return ALLOWED_TRANSITIONS.getOrDefault(current, Set.of()).contains(target);
    }

    private static Map<OrderStatus, Set<OrderStatus>> allowedTransitions() {
        EnumMap<OrderStatus, Set<OrderStatus>> transitions = new EnumMap<>(OrderStatus.class);
        transitions.put(OrderStatus.CREADO, EnumSet.of(OrderStatus.ACEPTADO, OrderStatus.CANCELADO));
        transitions.put(OrderStatus.ACEPTADO, EnumSet.of(OrderStatus.EN_PREPARACION, OrderStatus.CANCELADO));
        transitions.put(OrderStatus.EN_PREPARACION, EnumSet.of(OrderStatus.DESPACHADO, OrderStatus.CANCELADO));
        transitions.put(OrderStatus.DESPACHADO, EnumSet.of(OrderStatus.ENTREGADO));
        transitions.put(OrderStatus.ENTREGADO, EnumSet.noneOf(OrderStatus.class));
        transitions.put(OrderStatus.CANCELADO, EnumSet.noneOf(OrderStatus.class));
        return Map.copyOf(transitions);
    }
}
