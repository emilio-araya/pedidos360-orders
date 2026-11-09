package com.pedidos360.orders.events;

import com.pedidos360.orders.order.domain.OrderStatus;

/** Tipos de evento de dominio del contrato `pedidos360.order-events`. */
public enum OrderEventType {
    ORDER_CREATED,
    ORDER_UPDATED,
    ORDER_ACCEPTED,
    ORDER_IN_PREPARATION,
    ORDER_DISPATCHED,
    ORDER_DELIVERED,
    ORDER_CANCELLED;

    /** Evento que corresponde a un pedido que acaba de pasar al estado indicado. */
    public static OrderEventType forTransitionTo(OrderStatus target) {
        return switch (target) {
            case ACEPTADO -> ORDER_ACCEPTED;
            case EN_PREPARACION -> ORDER_IN_PREPARATION;
            case DESPACHADO -> ORDER_DISPATCHED;
            case ENTREGADO -> ORDER_DELIVERED;
            case CANCELADO -> ORDER_CANCELLED;
            case CREADO -> throw new IllegalArgumentException("CREADO no es destino de una transición");
        };
    }
}
