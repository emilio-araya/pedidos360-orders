-- Enruta las filas de la outbox de Kafka: cada fila declara su tópico destino.
-- Por defecto sigue siendo `pedidos360.order-events`; las intenciones de
-- notificación (NOTIFICATION_REQUESTED) llevan `pedidos360.notification-events`.
ALTER TABLE kafka_outbox_event ADD (
    target_topic VARCHAR(249) DEFAULT 'pedidos360.order-events' NOT NULL
);
