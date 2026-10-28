package com.pedidos360.orders.messaging;

import com.rabbitmq.client.Channel;
import java.io.IOException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

/**
 * Adaptador de transporte: recibe el mensaje crudo de RabbitMQ y lo entrega al
 * handler de negocio a través de {@link ResilientConsumer}, que decide ack,
 * reintento o DLQ. El handler no conoce el broker.
 */
@Component
public class MessagingEntryPoints {

    private final ResilientConsumer consumer;
    private final StockResultListener stockResultListener;

    public MessagingEntryPoints(ResilientConsumer consumer, StockResultListener stockResultListener) {
        this.consumer = consumer;
        this.stockResultListener = stockResultListener;
    }

    @RabbitListener(queues = MessagingConfiguration.RESULT_QUEUE)
    public void onStockResult(Message message, Channel channel) throws IOException {
        consumer.consume(message, channel, MessagingConfiguration.RESULT_QUEUE,
                StockResultMessage.class, stockResultListener::onStockResult);
    }
}
