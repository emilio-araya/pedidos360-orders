package com.pedidos360.orders.order.exception;

/**
 * Parámetros de paginación fuera del rango permitido.
 *
 * <p>Se rechaza en lugar de recortar en silencio: un límite invisible hace que
 * el cliente crea que pidió algo distinto de lo que recibió.
 */
public class InvalidPageRequestException extends RuntimeException {

    public InvalidPageRequestException(String message) {
        super(message);
    }
}
