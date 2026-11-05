-- Outbox de eventos de dominio hacia Kafka (topic pedidos360.order-events).
-- Es independiente de outbox_event (RabbitMQ): un pedido genera un evento por
-- cada cambio de estado, así que aquí no hay unicidad por (agregado, tipo).
CREATE TABLE kafka_outbox_event (
    id VARCHAR(36) NOT NULL,
    aggregate_id VARCHAR(36) NOT NULL,
    event_type VARCHAR(60) NOT NULL,
    payload CLOB NOT NULL,
    created_at TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    published_at TIMESTAMP(6) WITH TIME ZONE,
    attempts INTEGER DEFAULT 0 NOT NULL,
    CONSTRAINT pk_kafka_outbox_event PRIMARY KEY (id),
    CONSTRAINT ck_kafka_outbox_event_attempts CHECK (attempts >= 0)
);

CREATE INDEX idx_kafka_outbox_event_pending ON kafka_outbox_event (published_at, created_at);
