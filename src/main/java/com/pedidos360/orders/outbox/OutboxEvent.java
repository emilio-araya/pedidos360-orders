package com.pedidos360.orders.outbox;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;
import java.util.UUID;

/**
 * Evento de dominio pendiente de publicar al broker.
 *
 * Se escribe en la misma transacción que el cambio de estado del pedido, de modo
 * que no exista un estado local sin su evento ni un evento sin su estado. El
 * publicador lo marca como publicado recién cuando el broker confirma.
 */
@Entity
@Table(
        name = "outbox_event",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_outbox_event_aggregate_type",
                columnNames = {"aggregate_id", "event_type"}
        )
)
public class OutboxEvent {

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

    protected OutboxEvent() {
    }

    public OutboxEvent(UUID aggregateId, String eventType, String payload, Instant createdAt) {
        this.id = UUID.randomUUID().toString();
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
