package com.pedidos360.orders.notification;

import com.pedidos360.orders.events.NotificationRequested;
import java.time.Instant;
import java.util.Optional;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Idempotencia del envío de notificaciones sobre SMTP. Kafka entrega
 * at-least-once: tras un fallo antes del commit del offset, el mismo evento
 * puede volver a llegar. `event_id` (el de la outbox) es la clave natural de
 * este registro.
 *
 * Regla de salida:
 * <ul>
 *   <li>fila SENT → nunca se vuelve a enviar;</li>
 *   <li>fila PROCESSING fresca (&lt; 10 min) → otro hilo la está atendiendo: se omite;</li>
 *   <li>sin fila, PENDING, FAILED o PROCESSING huérfana → se intenta el envío;</li>
 *   <li>en carrera por insertar la misma fila, gana la primera y la segunda se omite.</li>
 * </ul>
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

    /** @return `true` si este llamado debe proceder al envío. */
    @Transactional
    public boolean tryPrepare(NotificationRequested notification, Instant now) {
        String eventId = notification.eventId().toString();
        Optional<NotificationDelivery> existing = repository.findByEventId(eventId);
        if (existing.isPresent()) {
            NotificationDelivery row = existing.get();
            if (row.isSent() || row.isInFlight(now)) {
                return false;
            }
            row.rebegin(now);
            repository.save(row);
            return true;
        }
        try {
            repository.save(NotificationDelivery.begin(
                    eventId, notification.orderNumber(), notification.email(), now));
            return true;
        } catch (DataIntegrityViolationException exception) {
            // Carrera con otra instancia: otro llegó primero con este eventId.
            // Releer decide: si el ganador ya envió o tiene envío en curso, se
            // omite (mejor duplicar menos que más).
            return repository.findByEventId(eventId)
                    .map(row -> !row.isSent() && !row.isInFlight(now))
                    .orElse(false);
        }
    }

    @Transactional
    public void markSent(String eventId, Instant now) {
        repository.findByEventId(eventId).ifPresent(row -> {
            row.markSent(now);
            repository.save(row);
        });
    }

    @Transactional
    public void markFailed(String eventId, Instant now, String error) {
        repository.findByEventId(eventId).ifPresent(row -> {
            row.markFailed(now, error);
            repository.save(row);
        });
    }
}
