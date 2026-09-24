CREATE TABLE orders (
    id VARCHAR(36) NOT NULL,
    order_number VARCHAR(12) NOT NULL,
    customer_id VARCHAR(255) NOT NULL,
    status VARCHAR(20) NOT NULL,
    notes VARCHAR(1000),
    total NUMBER(19, 2) NOT NULL,
    stock_reserved NUMBER(1) NOT NULL,
    created_at TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    version ${version_type} NOT NULL,
    CONSTRAINT pk_orders PRIMARY KEY (id),
    CONSTRAINT uk_orders_order_number UNIQUE (order_number),
    CONSTRAINT ck_orders_status CHECK (
        status IN ('CREADO', 'ACEPTADO', 'EN_PREPARACION', 'DESPACHADO', 'ENTREGADO', 'CANCELADO')
    ),
    CONSTRAINT ck_orders_stock_reserved CHECK (stock_reserved IN (0, 1)),
    CONSTRAINT ck_orders_total CHECK (total >= 0)
);

CREATE TABLE order_items (
    id VARCHAR(36) NOT NULL,
    order_id VARCHAR(36) NOT NULL,
    product_id VARCHAR(36) NOT NULL,
    product_name VARCHAR(300) NOT NULL,
    quantity INTEGER NOT NULL,
    unit_price NUMBER(19, 2) NOT NULL,
    CONSTRAINT pk_order_items PRIMARY KEY (id),
    CONSTRAINT fk_order_items_order FOREIGN KEY (order_id) REFERENCES orders (id),
    CONSTRAINT ck_order_items_quantity CHECK (quantity > 0),
    CONSTRAINT ck_order_items_unit_price CHECK (unit_price > 0)
);

CREATE INDEX idx_orders_customer_id ON orders (customer_id);
CREATE INDEX idx_orders_status ON orders (status);
CREATE INDEX idx_order_items_order_id ON order_items (order_id);
