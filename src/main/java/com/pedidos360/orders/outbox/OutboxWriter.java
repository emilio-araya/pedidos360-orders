package com.pedidos360.orders.outbox;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Escribe eventos en la outbox. Debe invocarse dentro de la transacción que
 * cambia el estado del agregado: si esa transacción hace rollback, el evento
 * desaparece con ella.
 */
@Component
public class OutboxWriter {

    private final OutboxEventRepository repository;
    private final ObjectMapper objectMapper;

    public OutboxWriter(OutboxEventRepository repository, ObjectMapper objectMapper) {
        this.repository = repository;
        this.objectMapper = objectMapper;
    }

    public OutboxEvent write(UUID aggregateId, String eventType, Object payload) {
        String json;
        try {
            json = objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException(
                    "No se pudo serializar el evento " + eventType + " del agregado " + aggregateId,
                    exception);
        }
        return repository.save(new OutboxEvent(aggregateId, eventType, json, Instant.now()));
    }

    /**
     * Escribe el evento sólo si el agregado todavía no lo tiene. Se usa para
     * respuestas a mensajes que pueden reentregarse; quien llama debe tener el
     * agregado bloqueado para que la comprobación y la escritura no compitan.
     */
    public void writeIfAbsent(UUID aggregateId, String eventType, Object payload) {
        if (!repository.existsByAggregateIdAndEventType(aggregateId.toString(), eventType)) {
            write(aggregateId, eventType, payload);
        }
    }
}
