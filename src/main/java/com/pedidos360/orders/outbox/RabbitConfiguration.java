package com.pedidos360.orders.outbox;

import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Topología de mensajería del lado del productor de eventos de pedido.
 *
 * Regla de propiedad: cada servicio declara el exchange al que publica y las
 * colas que consume. Las colas `stock.reserve` y `stock.release` las declara
 * el catálogo, que es quien las consume; si ambos lados las declararan, la
 * primera diferencia de argumentos (por ejemplo, la DLQ) haría fallar la
 * declaración con PRECONDITION_FAILED.
 *
 * Mientras el consumidor no declare su cola, un evento no tiene destino: con
 * mandatory activo vuelve como returned, el publicador lo trata como fallo y
 * el evento queda pendiente en la outbox. Nada se descarta en silencio.
 */
@Configuration
public class RabbitConfiguration {

    public static final String EVENTS_EXCHANGE = "pedidos360.events";
    public static final String ACCEPTED_ROUTING_KEY = "pedido.aceptado";
    public static final String CANCELLED_ROUTING_KEY = "pedido.cancelado";
    /** Reserva confirmada para un pedido ya cancelado: el catálogo debe liberarla. */
    public static final String ORPHAN_ROUTING_KEY = "pedido.reserva-huerfana";

    @Bean
    public TopicExchange eventsExchange() {
        return new TopicExchange(EVENTS_EXCHANGE, true, false);
    }

    @Bean
    public RabbitTemplate rabbitTemplate(ConnectionFactory connectionFactory) {
        RabbitTemplate template = new RabbitTemplate(connectionFactory);
        template.setMandatory(true);
        return template;
    }
}
