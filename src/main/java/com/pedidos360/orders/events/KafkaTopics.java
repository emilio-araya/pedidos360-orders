package com.pedidos360.orders.events;

/** Nombres de los tópicos de Kafka que publica este servicio. */
public final class KafkaTopics {

    public static final String ORDER_EVENTS = "pedidos360.order-events";
    public static final String NOTIFICATION_EVENTS = "pedidos360.notification-events";

    private KafkaTopics() {
    }
}
