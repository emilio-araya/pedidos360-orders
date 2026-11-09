package com.pedidos360.orders.events;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import com.pedidos360.orders.order.domain.OrderStatus;
import java.util.List;
import java.util.UUID;

/**
 * Valor JSON publicado en `pedidos360.order-events` (schemaVersion 1). Es el
 * contrato con el catálogo: cambiar un campo exige subir `schemaVersion`.
 *
 * `previousStatus` se serializa siempre, también como null en ORDER_CREATED,
 * aunque la configuración global de Jackson omita los nulos.
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
@JsonPropertyOrder({
        "eventId", "eventType", "schemaVersion", "orderId",
        "status", "previousStatus", "items", "occurredAt"
})
public record OrderDomainEvent(
        UUID eventId,
        OrderEventType eventType,
        int schemaVersion,
        UUID orderId,
        OrderStatus status,
        OrderStatus previousStatus,
        List<Item> items,
        // Instant.toString(): ISO-8601 en UTC, sin depender de la configuración de Jackson.
        String occurredAt
) {

    public static final int SCHEMA_VERSION = 1;

    public record Item(UUID productId, int quantity) {
    }
}
