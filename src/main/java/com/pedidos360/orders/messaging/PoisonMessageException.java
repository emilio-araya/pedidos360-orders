package com.pedidos360.orders.messaging;

/**
 * Mensaje que nunca podrá procesarse, por más que se reintente: cuerpo
 * ilegible, campos obligatorios ausentes o un pedido que no existe. Va directo
 * a la DLQ en lugar de agotar los reintentos.
 */
public class PoisonMessageException extends RuntimeException {

    public PoisonMessageException(String message) {
        super(message);
    }

    public PoisonMessageException(String message, Throwable cause) {
        super(message, cause);
    }
}
