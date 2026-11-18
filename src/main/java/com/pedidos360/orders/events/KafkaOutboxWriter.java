package com.pedidos360.orders.events;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pedidos360.orders.order.domain.Order;
import com.pedidos360.orders.order.domain.OrderStatus;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Registra los eventos de dominio del pedido en la outbox de Kafka. Debe
 * invocarse dentro de la transacción que cambia el pedido: si esa transacción
 * hace rollback, el evento desaparece con ella.
 */
@Component
public class KafkaOutboxWriter {

    private final KafkaOutboxEventRepository repository;
    private final ObjectMapper objectMapper;

    public KafkaOutboxWriter(KafkaOutboxEventRepository repository, ObjectMapper objectMapper) {
        this.repository = repository;
        this.objectMapper = objectMapper;
    }

    /**
     * Registra el evento con el estado actual del pedido y sus items.
     *
     * @param previousStatus estado anterior al cambio; null sólo en ORDER_CREATED
     */
    public KafkaOutboxEvent record(Order order, OrderStatus previousStatus, OrderEventType eventType) {
        UUID eventId = UUID.randomUUID();
        UUID orderId = UUID.fromString(order.getId());
        Instant now = Instant.now();
        List<OrderDomainEvent.Item> items = order.getItems().stream()
                .map(item -> new OrderDomainEvent.Item(
                        UUID.fromString(item.getProductId()),
                        item.getQuantity()))
                .toList();
        OrderDomainEvent event = new OrderDomainEvent(
                eventId,
                eventType,
                OrderDomainEvent.SCHEMA_VERSION,
                orderId,
                order.getStatus(),
                previousStatus,
                items,
                now.toString());
        KafkaOutboxEvent domainEvent = repository.save(new KafkaOutboxEvent(
                eventId, orderId, eventType.name(), toJson(event), now));
        // Un invitado no ve la app: hay que avisarle cada cambio por correo.
        // Sólo transiciones (previousStatus != null): el pedido recién creado
        // ya se lo informó el correo de voucher. Se encola en la MISMA
        // transacción, así que si el cambio de estado hace rollback, el aviso
        // desaparece con él.
        if (previousStatus != null && order.getCustomerEmail() != null) {
            enqueueNotification(order, orderId, previousStatus, now);
        }
        return domainEvent;
    }

    private void enqueueNotification(Order order, UUID orderId, OrderStatus previousStatus, Instant now) {
        UUID notificationId = UUID.randomUUID();
        NotificationRequested notification = new NotificationRequested(
                notificationId,
                orderId,
                order.getOrderNumber(),
                order.getStatus().name(),
                previousStatus.name(),
                order.getCustomerEmail(),
                now.toString());
        repository.save(new KafkaOutboxEvent(notificationId, orderId, "NOTIFICATION_REQUESTED",
                toJson(notification), now, KafkaTopics.NOTIFICATION_EVENTS));
    }

    private String toJson(Object event) {
        try {
            return objectMapper.writeValueAsString(event);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException(
                    "No se pudo serializar un evento de la outbox: " + event, exception);
        }
    }
}
