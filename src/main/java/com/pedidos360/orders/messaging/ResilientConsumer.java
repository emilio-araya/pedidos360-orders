package com.pedidos360.orders.messaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rabbitmq.client.Channel;
import java.io.IOException;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Entrega con ack manual, reintentos y DLQ.
 *
 * <ul>
 *   <li>Éxito: ack, sólo después de que el handler terminó.</li>
 *   <li>Veneno (cuerpo ilegible o {@link PoisonMessageException}): nack sin
 *       reencolar; el broker lo mueve a la DLQ.</li>
 *   <li>Fallo transitorio: se publica una copia en la siguiente cola de
 *       retardo y, confirmada esa copia, ack del original. Agotados los
 *       niveles, nack sin reencolar: DLQ.</li>
 *   <li>Si la copia no se puede publicar: nack con reencolado, para no perder
 *       el mensaje.</li>
 * </ul>
 *
 * Ningún camino descarta un mensaje en silencio.
 */
@Component
public class ResilientConsumer {

    public static final String RETRY_COUNT_HEADER = "x-retry-count";

    private static final Logger log = LoggerFactory.getLogger(ResilientConsumer.class);

    private final RabbitTemplate rabbitTemplate;
    private final ObjectMapper objectMapper;
    private final long confirmTimeoutMs;

    public ResilientConsumer(
            RabbitTemplate rabbitTemplate,
            ObjectMapper objectMapper,
            @Value("${app.messaging.confirm-timeout-ms:5000}") long confirmTimeoutMs
    ) {
        this.rabbitTemplate = rabbitTemplate;
        this.objectMapper = objectMapper;
        this.confirmTimeoutMs = confirmTimeoutMs;
    }

    public <T> void consume(
            Message message,
            Channel channel,
            String queue,
            Class<T> type,
            Consumer<T> handler
    ) throws IOException {
        long deliveryTag = message.getMessageProperties().getDeliveryTag();
        String messageId = message.getMessageProperties().getMessageId();
        try {
            handler.accept(read(message, type));
            channel.basicAck(deliveryTag, false);
        } catch (PoisonMessageException poison) {
            log.error("Mensaje {} de {} enviado a la DLQ: {}", messageId, queue, poison.getMessage());
            channel.basicNack(deliveryTag, false, false);
        } catch (RuntimeException failure) {
            retryOrDeadLetter(message, channel, queue, failure);
        }
    }

    private void retryOrDeadLetter(Message message, Channel channel, String queue, RuntimeException failure)
            throws IOException {
        long deliveryTag = message.getMessageProperties().getDeliveryTag();
        String messageId = message.getMessageProperties().getMessageId();
        int nextAttempt = retryCount(message) + 1;
        if (nextAttempt > RetryTopology.DELAYS.size()) {
            log.error("Mensaje {} de {} agotó {} reintentos; enviado a la DLQ: {}",
                    messageId, queue, RetryTopology.DELAYS.size(), failure.getMessage());
            channel.basicNack(deliveryTag, false, false);
            return;
        }
        try {
            scheduleRetry(message, queue, nextAttempt);
            log.warn("Mensaje {} de {} falló ({}); reintento {} en {}",
                    messageId, queue, failure.getMessage(), nextAttempt,
                    RetryTopology.DELAYS.get(nextAttempt - 1));
            channel.basicAck(deliveryTag, false);
        } catch (RuntimeException scheduleFailure) {
            log.error("No se pudo programar el reintento del mensaje {} de {}; vuelve a la cola: {}",
                    messageId, queue, scheduleFailure.getMessage());
            channel.basicNack(deliveryTag, false, true);
        }
    }

    private <T> T read(Message message, Class<T> type) {
        String contentType = message.getMessageProperties().getContentType();
        if (contentType == null || !contentType.contains("json")) {
            throw new PoisonMessageException("Content type no soportado: " + contentType);
        }
        try {
            return objectMapper.readValue(message.getBody(), type);
        } catch (IOException exception) {
            throw new PoisonMessageException("Cuerpo JSON ilegible", exception);
        }
    }

    private int retryCount(Message message) {
        Object value = message.getMessageProperties().getHeader(RETRY_COUNT_HEADER);
        return value instanceof Number number ? number.intValue() : 0;
    }

    private void scheduleRetry(Message message, String queue, int attempt) {
        Message copy = MessageBuilder.fromClonedMessage(message)
                .setHeader(RETRY_COUNT_HEADER, attempt)
                .setDeliveryMode(MessageDeliveryMode.PERSISTENT)
                .build();
        CorrelationData correlation = new CorrelationData(UUID.randomUUID().toString());
        rabbitTemplate.send(RetryTopology.RETRY_EXCHANGE, RetryTopology.retryQueue(queue, attempt), copy, correlation);

        CorrelationData.Confirm confirm = awaitConfirm(correlation);
        if (!confirm.isAck()) {
            throw new IllegalStateException("El broker rechazó el reintento: " + confirm.getReason());
        }
        if (correlation.getReturned() != null) {
            throw new IllegalStateException(
                    "La cola de reintento no existe: " + correlation.getReturned().getReplyText());
        }
    }

    private CorrelationData.Confirm awaitConfirm(CorrelationData correlation) {
        try {
            CorrelationData.Confirm confirm = correlation.getFuture()
                    .get(confirmTimeoutMs, TimeUnit.MILLISECONDS);
            if (confirm == null) {
                throw new IllegalStateException("El broker no confirmó el reintento");
            }
            return confirm;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrumpido esperando la confirmación del reintento", exception);
        } catch (ExecutionException | TimeoutException exception) {
            throw new IllegalStateException(
                    "Sin confirmación del reintento en " + confirmTimeoutMs + " ms", exception);
        }
    }
}
