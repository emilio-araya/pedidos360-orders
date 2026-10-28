package com.pedidos360.orders.order.exception;

/**
 * A tracking token that is malformed or unknown. The message is the same in
 * both cases and never includes the token.
 */
public class GuestOrderNotFoundException extends RuntimeException {

    public GuestOrderNotFoundException() {
        super("No existe un pedido para el enlace de seguimiento");
    }
}
