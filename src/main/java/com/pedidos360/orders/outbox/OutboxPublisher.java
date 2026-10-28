package com.pedidos360.orders.outbox;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Publica los eventos pendientes de la outbox.
 *
 * Garantía: at-least-once. Un evento se marca como publicado sólo cuando el
 * broker confirma y hay una cola que lo recibió. Si el proceso muere entre la
 * publicación y el commit, el evento se reenvía; los consumidores son
 * idempotentes, así que el duplicado no tiene efecto.
 */
@Component
@ConditionalOnProperty(
        name = "app.outbox.publisher-enabled",
        havingValue = "true",
        matchIfMissing = true
)
public class OutboxPublisher {

    private static final Logger log = LoggerFactory.getLogger(OutboxPublisher.class);

    private final OutboxEventRepository repository;
    private final RabbitTemplate rabbitTemplate;
    private final long confirmTimeoutMs;

    public OutboxPublisher(
            OutboxEventRepository repository,
            RabbitTemplate rabbitTemplate,
            @Value("${app.outbox.confirm-timeout-ms:5000}") long confirmTimeoutMs
    ) {
        this.repository = repository;
        this.rabbitTemplate = rabbitTemplate;
        this.confirmTimeoutMs = confirmTimeoutMs;
    }

    @Scheduled(fixedDelayString = "${app.outbox.publisher-delay-ms:2000}")
    public void publishPending() {
        List<OutboxEvent> pending = repository.findTop50ByPublishedAtIsNullOrderByCreatedAtAsc();
        for (OutboxEvent event : pending) {
            try {
                publish(event);
            } catch (RuntimeException exception) {
                log.warn("No se pudo publicar el evento {} del pedido {}: {}",
                        event.getEventType(), event.getAggregateId(), exception.getMessage());
                event.registerFailedAttempt();
                repository.save(event);
                // Se corta el lote para no alterar el orden de los eventos de un mismo agregado.
                return;
            }
        }
    }

    private void publish(OutboxEvent event) {
        CorrelationData correlation = new CorrelationData(event.getId());
        rabbitTemplate.send(
                RabbitConfiguration.EVENTS_EXCHANGE,
                event.getEventType(),
                toMessage(event),
                correlation);

        CorrelationData.Confirm confirm = awaitConfirm(correlation);
        if (!confirm.isAck()) {
            throw new IllegalStateException("El broker rechazó el evento: " + confirm.getReason());
        }
        if (correlation.getReturned() != null) {
            throw new IllegalStateException(
                    "El evento no tiene cola destino: " + correlation.getReturned().getReplyText());
        }

        event.markPublished(Instant.now());
        repository.save(event);
    }

    /**
     * El payload ya es JSON serializado en la outbox: se envía tal cual, sin
     * pasar por un conversor que lo volvería a serializar como string. El
     * content type es obligatorio para que el consumidor lo deserialice, y el
     * messageId permite rastrear un duplicado hasta su evento de origen.
     */
    private Message toMessage(OutboxEvent event) {
        return MessageBuilder.withBody(event.getPayload().getBytes(StandardCharsets.UTF_8))
                .setContentType(MessageProperties.CONTENT_TYPE_JSON)
                .setContentEncoding(StandardCharsets.UTF_8.name())
                .setDeliveryMode(MessageDeliveryMode.PERSISTENT)
                .setMessageId(event.getId())
                .build();
    }

    private CorrelationData.Confirm awaitConfirm(CorrelationData correlation) {
        try {
            CorrelationData.Confirm confirm = correlation.getFuture()
                    .get(confirmTimeoutMs, TimeUnit.MILLISECONDS);
            if (confirm == null) {
                throw new IllegalStateException("El broker no confirmó el evento");
            }
            return confirm;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrumpido esperando la confirmación del broker", exception);
        } catch (ExecutionException | TimeoutException exception) {
            throw new IllegalStateException(
                    "Sin confirmación del broker en " + confirmTimeoutMs + " ms", exception);
        }
    }
}
