package com.pedidos360.orders.notification;

import com.pedidos360.orders.events.NotificationRequested;
import java.time.Instant;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Idempotencia del envío de notificaciones sobre SMTP. Kafka entrega
 * at-least-once: tras un fallo antes del commit del offset, el mismo evento
 * puede volver a llegar. `event_id` (el de la outbox) es la clave natural.
 *
 * La reclamación de una entrega existente se hace bajo bloqueo pesimista:
 * sólo una instancia puede pasar de FAILED/PROCESSING huérfano a PROCESSING.
 * Si dos instancias intentan insertar por primera vez el mismo evento, la
 * clave primaria deja que una gane; la otra recibe un error transitorio de
 * persistencia y Kafka reintenta, momento en el que verá la fila PROCESSING.
 *
 * SMTP no ofrece exactly-once: entre que el servidor acepta el correo y se
 * marca SENT hay una ventana de duplicado inevitable; se reduce al mínimo y
 * queda documentada.
 */
@Service
public class NotificationDeliveryService {

    private final NotificationDeliveryRepository repository;

    public NotificationDeliveryService(NotificationDeliveryRepository repository) {
        this.repository = repository;
    }

    /** @return true sólo si esta instancia reclamó el derecho a enviar. */
    @Transactional
    public boolean tryPrepare(NotificationRequested notification, Instant now) {
        String eventId = notification.eventId().toString();
        Optional<NotificationDelivery> existing = repository.findByEventIdForUpdate(eventId);
        if (existing.isPresent()) {
            NotificationDelivery row = existing.get();
            if (row.isSent() || row.isInFlight(now)) {
                return false;
            }
            row.rebegin(now);
            repository.save(row);
            return true;
        }

        // Flush dentro de esta transacción para que una colisión de PK se
        // propague al listener y active el retry de Kafka. No se intenta leer
        // de nuevo dentro de una transacción que ya quedó marcada rollback-only.
        repository.saveAndFlush(NotificationDelivery.begin(
                eventId, notification.orderNumber(), notification.email(), now));
        return true;
    }

    @Transactional
    public void markSent(String eventId, Instant now) {
        repository.findByEventIdForUpdate(eventId).ifPresent(row -> {
            row.markSent(now);
            repository.save(row);
        });
    }

    @Transactional
    public void markFailed(String eventId, Instant now, String error) {
        repository.findByEventIdForUpdate(eventId).ifPresent(row -> {
            row.markFailed(now, error);
            repository.save(row);
        });
    }
}
