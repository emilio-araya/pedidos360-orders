package com.pedidos360.orders.messaging;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Declarables;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Topología del lado consumidor de `orders`: la cola de resultados de reserva
 * la declara este servicio, que es quien la consume.
 */
@Configuration
public class MessagingConfiguration {

    public static final String RESULT_QUEUE = "orders.stock-result";
    public static final String RESERVED_ROUTING_KEY = "stock.reservado";
    public static final String FAILED_ROUTING_KEY = "stock.reserva-fallida";

    @Bean
    public Queue stockResultQueue() {
        return RetryTopology.mainQueue(RESULT_QUEUE);
    }

    @Bean
    public Declarables stockResultRetryTopology() {
        return RetryTopology.declarables(RESULT_QUEUE);
    }

    @Bean
    public Binding stockReservedBinding(Queue stockResultQueue, TopicExchange eventsExchange) {
        return BindingBuilder.bind(stockResultQueue).to(eventsExchange).with(RESERVED_ROUTING_KEY);
    }

    @Bean
    public Binding stockFailedBinding(Queue stockResultQueue, TopicExchange eventsExchange) {
        return BindingBuilder.bind(stockResultQueue).to(eventsExchange).with(FAILED_ROUTING_KEY);
    }
}
