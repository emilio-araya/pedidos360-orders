package com.pedidos360.orders.order.exception;

import com.pedidos360.orders.order.domain.OrderStatus;

public class InvalidOrderTransitionException extends RuntimeException {

    public InvalidOrderTransitionException(OrderStatus current, OrderStatus target) {
        super("Transición no permitida: " + current + " -> " + target);
    }
}
