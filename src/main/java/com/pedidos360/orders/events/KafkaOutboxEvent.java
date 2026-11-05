package com.pedidos360.orders.events;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * Evento de dominio del pedido pendiente de publicar en Kafka.
 *
 * Se escribe en la misma transacción que el cambio de estado del pedido. El id
 * de la fila es el `eventId` del contrato, de modo que un reenvío lleva el mismo
 * identificador y el consumidor lo puede descartar.
 */
@Entity
@Table(name = "kafka_outbox_event")
public class KafkaOutboxEvent {

    @Id
    @Column(name = "id", nullable = false, updatable = false, length = 36)
    private String id;

    @Column(name = "aggregate_id", nullable = false, updatable = false, length = 36)
    private String aggregateId;

    @Column(name = "event_type", nullable = false, updatable = false, length = 60)
    private String eventType;

    @Lob
    @Column(name = "payload", nullable = false, updatable = false)
    private String payload;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "published_at")
    private Instant publishedAt;

    @Column(name = "attempts", nullable = false)
    private int attempts;

    protected KafkaOutboxEvent() {
    }

    public KafkaOutboxEvent(UUID eventId, UUID aggregateId, String eventType, String payload, Instant createdAt) {
        this.id = eventId.toString();
        this.aggregateId = aggregateId.toString();
        this.eventType = eventType;
        this.payload = payload;
        this.createdAt = createdAt;
        this.attempts = 0;
    }

    public void markPublished(Instant when) {
        this.publishedAt = when;
    }

    public void registerFailedAttempt() {
        this.attempts++;
    }

    public String getId() {
        return id;
    }

    public String getAggregateId() {
        return aggregateId;
    }

    public String getEventType() {
        return eventType;
    }

    public String getPayload() {
        return payload;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getPublishedAt() {
        return publishedAt;
    }

    public int getAttempts() {
        return attempts;
    }
}
