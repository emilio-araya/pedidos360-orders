package com.pedidos360.orders.outbox;

import java.util.List;
import java.util.UUID;

/**
 * Cuerpos JSON de los eventos de pedido. Son parte del contrato con el
 * consumidor de catálogo: cambiarlos rompe la reserva de stock.
 */
public final class OrderEventPayload {

    private OrderEventPayload() {
    }

    public record Accepted(UUID orderId, List<Item> items) {
    }

    public record Cancelled(UUID orderId) {
    }

    public record Item(UUID productId, int quantity) {
    }
}
