package com.pedidos360.orders.messaging;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.Declarable;
import org.springframework.amqp.core.Queue;

/**
 * Cada cola consumida tiene tres colas de retardo (5 s, 30 s, 2 min) que la
 * devuelven a la cola original al vencer, y una DLQ a la que el broker mueve
 * todo lo que se rechaza sin reencolar.
 */
class RetryTopologyTest {

    private static final String QUEUE = "orders.stock-result";

    @Test
    void theMainQueueDeadLettersRejectedMessagesToItsDlq() {
        Queue main = RetryTopology.mainQueue(QUEUE);

        assertThat(main.isDurable()).isTrue();
        assertThat(main.getArguments()).containsEntry("x-dead-letter-exchange", RetryTopology.DEAD_LETTER_EXCHANGE)
                .containsEntry("x-dead-letter-routing-key", QUEUE);
    }

    @Test
    void eachRetryQueueWaitsItsDelayAndReturnsTheMessageToTheMainQueue() {
        List<Declarable> declarables = List.copyOf(RetryTopology.declarables(QUEUE).getDeclarables());

        assertThat(retryArguments(declarables, QUEUE + ".retry.1")).containsEntry("x-message-ttl", 5_000);
        assertThat(retryArguments(declarables, QUEUE + ".retry.2")).containsEntry("x-message-ttl", 30_000);
        assertThat(retryArguments(declarables, QUEUE + ".retry.3")).containsEntry("x-message-ttl", 120_000);
        for (int attempt = 1; attempt <= 3; attempt++) {
            assertThat(retryArguments(declarables, QUEUE + ".retry." + attempt))
                    .containsEntry("x-dead-letter-exchange", "")
                    .containsEntry("x-dead-letter-routing-key", QUEUE);
            assertThat(bindingTo(declarables, QUEUE + ".retry." + attempt).getExchange())
                    .isEqualTo(RetryTopology.RETRY_EXCHANGE);
        }
    }

    @Test
    void theDlqIsDurableAndBoundToTheDeadLetterExchange() {
        List<Declarable> declarables = List.copyOf(RetryTopology.declarables(QUEUE).getDeclarables());

        Queue dlq = queue(declarables, QUEUE + ".dlq");
        assertThat(dlq.isDurable()).isTrue();
        Binding binding = bindingTo(declarables, QUEUE + ".dlq");
        assertThat(binding.getExchange()).isEqualTo(RetryTopology.DEAD_LETTER_EXCHANGE);
        assertThat(binding.getRoutingKey()).isEqualTo(QUEUE);
    }

    private Map<String, Object> retryArguments(List<Declarable> declarables, String name) {
        return queue(declarables, name).getArguments();
    }

    private Queue queue(List<Declarable> declarables, String name) {
        return declarables.stream()
                .filter(Queue.class::isInstance)
                .map(Queue.class::cast)
                .filter(queue -> queue.getName().equals(name))
                .findFirst()
                .orElseThrow(() -> new AssertionError("No se declaró la cola " + name));
    }

    private Binding bindingTo(List<Declarable> declarables, String queueName) {
        return declarables.stream()
                .filter(Binding.class::isInstance)
                .map(Binding.class::cast)
                .filter(binding -> binding.getDestination().equals(queueName))
                .findFirst()
                .orElseThrow(() -> new AssertionError("No hay binding hacia " + queueName));
    }
}
