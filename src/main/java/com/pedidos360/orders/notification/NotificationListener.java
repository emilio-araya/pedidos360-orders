package com.pedidos360.orders.notification;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pedidos360.orders.events.KafkaTopics;
import com.pedidos360.orders.events.NotificationRequested;
import java.io.IOException;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Consumidor de `pedidos360.notification-events` (grupo `pedidos360-notifications`).
 * Envía un correo por transición de estado para los pedidos de invitado.
 *
 * Un payload inválido se descarta con error en el log (no bloquea la partición
 * ni lo reintenta en bucle); los fallos de SMTP los maneja
 * {@link NotificationKafkaConfiguration}.
 *
 * El contenedor sólo arranca con `app.kafka.notifications.enabled=true` (valor
 * por defecto); la suite lo apaga para no necesitar broker.
 */
@Component
public class NotificationListener {

    private static final Logger log = LoggerFactory.getLogger(NotificationListener.class);

    private final ObjectMapper objectMapper;
    private final OrderStatusMailer mailer;

    public NotificationListener(ObjectMapper objectMapper, OrderStatusMailer mailer) {
        this.objectMapper = objectMapper;
        this.mailer = mailer;
    }

    @KafkaListener(
            id = "guestStatusNotifications",
            topics = KafkaTopics.NOTIFICATION_EVENTS,
            groupId = "pedidos360-notifications",
            autoStartup = "${app.kafka.notifications.enabled:true}")
    public void onNotification(ConsumerRecord<String, String> record) {
        NotificationRequested notification = parse(record.value());
        if (notification == null) {
            return;
        }
        mailer.send(notification);
        log.info("Notificación de cambio de estado enviada para el pedido {} ({} → {})",
                notification.orderNumber(), notification.previousStatus(), notification.status());
    }

    private NotificationRequested parse(String payload) {
        NotificationRequested notification;
        try {
            notification = objectMapper.readValue(payload, NotificationRequested.class);
        } catch (IOException | IllegalArgumentException exception) {
            log.error("Notificación mal formada descartada (offset en el log del contenedor): {}",
                    exception.getMessage());
            return null;
        }
        if (notification == null
                || notification.orderNumber() == null
                || notification.status() == null
                || notification.previousStatus() == null
                || notification.email() == null
                || !notification.email().contains("@")) {
            log.error("Notificación incompleta descartada: faltan orderNumber/status/previousStatus/email");
            return null;
        }
        return notification;
    }
}
