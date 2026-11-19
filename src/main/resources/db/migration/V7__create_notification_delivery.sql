-- Registro idempotente de envíos de notificación a invitados.
-- `event_id` es el identificador natural (el de la outbox): una reentrega
-- de Kafka tras perder el offset no duplica el correo si la fila ya está SENT.
-- SMTP no garantiza exactly-once: esta tabla gestiona el duplicado posible y
-- deja constancia del resultado de cada intento.
CREATE TABLE notification_delivery (
    event_id VARCHAR(36) NOT NULL,
    order_number VARCHAR(40) NOT NULL,
    recipient_email VARCHAR(254) NOT NULL,
    status VARCHAR(16) NOT NULL,
    attempts INTEGER DEFAULT 0 NOT NULL,
    last_error VARCHAR(1000),
    created_at TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    sent_at TIMESTAMP(6) WITH TIME ZONE,
    CONSTRAINT pk_notification_delivery PRIMARY KEY (event_id),
    CONSTRAINT ck_notification_delivery_status CHECK (status IN ('PENDING', 'PROCESSING', 'SENT', 'FAILED')),
    CONSTRAINT ck_notification_delivery_attempts CHECK (attempts >= 0)
);
