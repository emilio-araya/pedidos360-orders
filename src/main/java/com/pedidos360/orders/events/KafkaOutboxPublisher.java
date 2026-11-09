package com.pedidos360.orders.events;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Publica en Kafka los eventos de dominio pendientes de la outbox.
 *
 * Garantía: at-least-once. Un evento se marca como publicado sólo cuando el
 * broker confirma la escritura (productor con `acks=all` e idempotencia). Si el
 * proceso muere entre el envío y el commit, el evento se reenvía con el mismo
 * `eventId`, y el consumidor lo descarta como duplicado.
 *
 * La clave del registro es el id del pedido: todos sus eventos caen en la misma
 * partición y Kafka conserva su orden.
 */
@Component
@ConditionalOnProperty(
        name = "app.kafka.outbox.publisher-enabled",
        havingValue = "true",
        matchIfMissing = true
)
public class KafkaOutboxPublisher {

    public static final String ORDER_EVENTS_TOPIC = "pedidos360.order-events";

    private static final Logger log = LoggerFactory.getLogger(KafkaOutboxPublisher.class);

    private final KafkaOutboxEventRepository repository;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final long sendTimeoutMs;

    public KafkaOutboxPublisher(
            KafkaOutboxEventRepository repository,
            KafkaTemplate<String, String> kafkaTemplate,
            @Value("${app.kafka.outbox.send-timeout-ms:10000}") long sendTimeoutMs
    ) {
        this.repository = repository;
        this.kafkaTemplate = kafkaTemplate;
        this.sendTimeoutMs = sendTimeoutMs;
    }

    @Scheduled(fixedDelayString = "${app.kafka.outbox.publisher-delay-ms:2000}")
    public void publishPending() {
        List<KafkaOutboxEvent> pending = repository.findTop50ByPublishedAtIsNullOrderByCreatedAtAsc();
        for (KafkaOutboxEvent event : pending) {
            try {
                publish(event);
            } catch (RuntimeException exception) {
                log.warn("No se pudo publicar en Kafka el evento {} del pedido {}: {}",
                        event.getEventType(), event.getAggregateId(), exception.getMessage());
                event.registerFailedAttempt();
                repository.save(event);
                // Se corta el lote para no adelantar eventos posteriores del mismo pedido.
                return;
            }
        }
    }

    /** El payload ya es JSON: viaja tal cual como valor String. */
    private void publish(KafkaOutboxEvent event) {
        awaitAck(kafkaTemplate.send(ORDER_EVENTS_TOPIC, event.getAggregateId(), event.getPayload()));
        event.markPublished(Instant.now());
        repository.save(event);
    }

    private void awaitAck(CompletableFuture<?> future) {
        try {
            future.get(sendTimeoutMs, TimeUnit.MILLISECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrumpido esperando la confirmación de Kafka", exception);
        } catch (ExecutionException exception) {
            throw new IllegalStateException("Kafka rechazó el evento: " + exception.getCause(), exception);
        } catch (TimeoutException exception) {
            throw new IllegalStateException(
                    "Sin confirmación de Kafka en " + sendTimeoutMs + " ms", exception);
        }
    }
}
