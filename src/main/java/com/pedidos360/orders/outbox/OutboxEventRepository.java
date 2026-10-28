package com.pedidos360.orders.outbox;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface OutboxEventRepository extends JpaRepository<OutboxEvent, String> {

    /**
     * Eventos todavía no confirmados por el broker, del más viejo al más nuevo.
     * El publicador los toma en ese orden para respetar la secuencia del agregado.
     */
    List<OutboxEvent> findTop50ByPublishedAtIsNullOrderByCreatedAtAsc();

    boolean existsByAggregateIdAndEventType(String aggregateId, String eventType);
}
