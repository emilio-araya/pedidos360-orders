package com.pedidos360.orders.notification;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pedidos360.orders.events.KafkaTopics;
import com.pedidos360.orders.events.NotificationRequested;
import java.io.IOException;
import java.time.Instant;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Consumidor de `pedidos360.notification-events` (grupo `pedidos360-notifications`).
 * Envía un correo por transición de estado para los pedidos de invitado.
 *
 * Un payload inválido o un fallo de envío que agote los reintentos termina en
 * `pedidos360.notification-events.DLT` (vía {@link NotificationKafkaConfiguration}),
 * no se pierde en silencio. La entrega es idempotente por `event_id`
 * ({@link NotificationDeliveryService}): un reenvío de Kafka no duplica el correo.
 *
 * El contenedor sólo arranca con `app.kafka.notifications.enabled=true` (valor
 * por defecto); la suite lo apaga para no necesitar broker.
 */
@Component
public class NotificationListener {

    private static final Logger log = LoggerFactory.getLogger(NotificationListener.class);

    private final ObjectMapper objectMapper;
    private final OrderStatusMailer mailer;
    private final NotificationDeliveryService deliveryService;

    public NotificationListener(ObjectMapper objectMapper, OrderStatusMailer mailer,
            NotificationDeliveryService deliveryService) {
        this.objectMapper = objectMapper;
        this.mailer = mailer;
        this.deliveryService = deliveryService;
    }

    @KafkaListener(
            id = "guestStatusNotifications",
            topics = KafkaTopics.NOTIFICATION_EVENTS,
            groupId = "pedidos360-notifications",
            autoStartup = "${app.kafka.notifications.enabled:true}")
    public void onNotification(ConsumerRecord<String, String> record) {
        NotificationRequested notification = parse(record);
        Instant now = Instant.now();
        if (!deliveryService.tryPrepare(notification, now)) {
            log.info("Notificación {} del pedido {} omitida: ya enviada o envío en curso "
                            + "(partición {}, offset {})",
                    notification.eventId(), notification.orderNumber(), record.partition(), record.offset());
            return;
        }
        try {
            mailer.send(notification);
            deliveryService.markSent(notification.eventId().toString(), Instant.now());
            log.info("Notificación de cambio de estado enviada para el pedido {} ({} → {})",
                    notification.orderNumber(), notification.previousStatus(), notification.status());
        } catch (RuntimeException exception) {
            deliveryService.markFailed(notification.eventId().toString(), Instant.now(), exception.getMessage());
            throw exception;
        }
    }

    private NotificationRequested parse(ConsumerRecord<String, String> record) {
        try {
            String payload = record.value();
            NotificationRequested notification = objectMapper.readValue(payload, NotificationRequested.class);
            if (notification == null
                    || notification.eventId() == null
                    || notification.orderNumber() == null
                    || notification.status() == null
                    || notification.previousStatus() == null
                    || notification.email() == null
                    || !notification.email().contains("@")) {
                throw new IllegalArgumentException(
                        "Notificación incompleta: faltan eventId/orderNumber/status/previousStatus/email");
            }
            return notification;
        } catch (IOException | IllegalArgumentException exception) {
            throw new IllegalArgumentException(
                    "Notificación mal formada (partición %d, offset %d): %s"
                            .formatted(record.partition(), record.offset(), exception.getMessage()),
                    exception);
        }
    }
}
