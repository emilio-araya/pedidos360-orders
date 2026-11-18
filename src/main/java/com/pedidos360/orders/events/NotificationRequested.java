package com.pedidos360.orders.events;

import java.util.UUID;

/**
 * Intención de notificación encolada en la outbox como fila de `kafka_outbox_event`
 * con `target_topic = pedidos360.notification-events` y `event_type` =
 * `NOTIFICATION_REQUESTED`. Se escribe en la misma transacción que el cambio de
 * estado, sólo para pedidos de invitado (tienen correo).
 *
 * No lleva el token de seguimiento crudo (sólo su hash se persiste): el correo
 * remite a la página genérica de seguimiento.
 */
public record NotificationRequested(
        UUID eventId,
        UUID orderId,
        String orderNumber,
        String status,
        String previousStatus,
        String email,
        String occurredAt) {
}
