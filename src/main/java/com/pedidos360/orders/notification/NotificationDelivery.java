package com.pedidos360.orders.notification;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * Resultado del envío de una notificación a un invitado. `event_id` es la
 * clave (viene del contrato): idempotencia frente a reentregas de Kafka.
 * Un envío SENT nunca se repite; PROCESSING con más de 10 minutos de
 * antigüedad se considera huérfano y puede reintentarse.
 */
@Entity
@Table(name = "notification_delivery")
public class NotificationDelivery {

    private static final long PROCESSING_STALE_SECONDS = 600;

    @Id
    @Column(name = "event_id", nullable = false, updatable = false, length = 36)
    private String eventId;

    @Column(name = "order_number", nullable = false, updatable = false, length = 40)
    private String orderNumber;

    @Column(name = "recipient_email", nullable = false, updatable = false, length = 254)
    private String recipientEmail;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private NotificationDeliveryStatus status;

    @Column(name = "attempts", nullable = false)
    private int attempts;

    @Column(name = "last_error", length = 1000)
    private String lastError;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "sent_at")
    private Instant sentAt;

    protected NotificationDelivery() {
    }

    private NotificationDelivery(String eventId, String orderNumber, String recipientEmail, Instant now) {
        this.eventId = eventId;
        this.orderNumber = orderNumber;
        this.recipientEmail = recipientEmail;
        this.status = NotificationDeliveryStatus.PROCESSING;
        this.attempts = 1;
        this.createdAt = now;
        this.updatedAt = now;
    }

    /** Fila nueva en estado PROCESSING (primer intento de esta reentrega). */
    public static NotificationDelivery begin(String eventId, String orderNumber, String recipientEmail, Instant now) {
        return new NotificationDelivery(eventId, orderNumber, recipientEmail, now);
    }

    public boolean isSent() {
        return status == NotificationDeliveryStatus.SENT;
    }

    /** Envío en curso por otra entrega; sólo se respeta si no está huérfano. */
    public boolean isInFlight(Instant now) {
        return status == NotificationDeliveryStatus.PROCESSING
                && updatedAt.isAfter(now.minusSeconds(PROCESSING_STALE_SECONDS));
    }

    /** Retoma un intento previo (PENDING/FAILED/PROCESSING huérfano). */
    public void rebegin(Instant now) {
        this.status = NotificationDeliveryStatus.PROCESSING;
        this.attempts += 1;
        this.lastError = null;
        this.updatedAt = now;
    }

    public void markSent(Instant now) {
        this.status = NotificationDeliveryStatus.SENT;
        this.sentAt = now;
        this.lastError = null;
        this.updatedAt = now;
    }

    public void markFailed(Instant now, String error) {
        this.status = NotificationDeliveryStatus.FAILED;
        this.lastError = error == null ? null : error.substring(0, Math.min(error.length(), 1000));
        this.updatedAt = now;
    }

    public String getEventId() {
        return eventId;
    }

    public NotificationDeliveryStatus getStatus() {
        return status;
    }

    public int getAttempts() {
        return attempts;
    }
}
