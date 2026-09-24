package com.pedidos360.orders.order.exception;

public class OrderNotFoundException extends RuntimeException {

    public OrderNotFoundException(String id) {
        super("No existe el pedido " + id);
    }
}
