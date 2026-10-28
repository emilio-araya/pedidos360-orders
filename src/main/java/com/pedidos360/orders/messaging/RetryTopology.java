package com.pedidos360.orders.messaging;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Declarable;
import org.springframework.amqp.core.Declarables;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;

/**
 * Reintentos con backoff exponencial y DLQ para una cola consumida.
 *
 * <pre>
 * cola ──fallo transitorio──▶ pedidos360.retry ──▶ cola.retry.N (TTL) ──vence──▶ cola
 *   └──rechazo sin reencolar (veneno o reintentos agotados)──▶ pedidos360.dlx ──▶ cola.dlq
 * </pre>
 *
 * Una cola de retardo por nivel, y no un TTL por mensaje en una sola cola: el
 * broker sólo expira la cabeza de la cola, así que un mensaje de 2 minutos
 * bloquearía a los de 5 segundos que tiene detrás.
 */
public final class RetryTopology {

    public static final String RETRY_EXCHANGE = "pedidos360.retry";
    public static final String DEAD_LETTER_EXCHANGE = "pedidos360.dlx";
    public static final List<Duration> DELAYS =
            List.of(Duration.ofSeconds(5), Duration.ofSeconds(30), Duration.ofMinutes(2));

    private RetryTopology() {
    }

    public static String retryQueue(String queue, int attempt) {
        return queue + ".retry." + attempt;
    }

    public static String deadLetterQueue(String queue) {
        return queue + ".dlq";
    }

    /** Cola principal: lo que se rechaza sin reencolar termina en su DLQ. */
    public static Queue mainQueue(String queue) {
        return QueueBuilder.durable(queue)
                .deadLetterExchange(DEAD_LETTER_EXCHANGE)
                .deadLetterRoutingKey(queue)
                .build();
    }

    /** Colas de retardo, DLQ y sus bindings para una cola principal. */
    public static Declarables declarables(String queue) {
        DirectExchange retryExchange = new DirectExchange(RETRY_EXCHANGE, true, false);
        DirectExchange deadLetterExchange = new DirectExchange(DEAD_LETTER_EXCHANGE, true, false);
        List<Declarable> declarables = new ArrayList<>(List.of(retryExchange, deadLetterExchange));

        for (int attempt = 1; attempt <= DELAYS.size(); attempt++) {
            String name = retryQueue(queue, attempt);
            Queue retry = QueueBuilder.durable(name)
                    .ttl((int) DELAYS.get(attempt - 1).toMillis())
                    .deadLetterExchange("")
                    .deadLetterRoutingKey(queue)
                    .build();
            declarables.add(retry);
            declarables.add(BindingBuilder.bind(retry).to(retryExchange).with(name));
        }

        Queue dlq = QueueBuilder.durable(deadLetterQueue(queue)).build();
        declarables.add(dlq);
        declarables.add(BindingBuilder.bind(dlq).to(deadLetterExchange).with(queue));
        return new Declarables(declarables);
    }
}
