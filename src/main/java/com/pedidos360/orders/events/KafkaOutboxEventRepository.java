package com.pedidos360.orders.events;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface KafkaOutboxEventRepository extends JpaRepository<KafkaOutboxEvent, String> {

    /**
     * Eventos todavía no confirmados por Kafka, del más viejo al más nuevo. El
     * publicador los toma en ese orden para respetar la secuencia de cada pedido.
     */
    List<KafkaOutboxEvent> findTop50ByPublishedAtIsNullOrderByCreatedAtAsc();
}
