package com.pedidos360.orders.catalog.exception;

public class CatalogConflictException extends RuntimeException {

    public CatalogConflictException(String message) {
        super(message);
    }

    public CatalogConflictException(String message, Throwable cause) {
        super(message, cause);
    }
}
