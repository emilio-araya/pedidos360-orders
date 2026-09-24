package com.pedidos360.orders.catalog.exception;

import java.util.UUID;

public class ProductNotFoundException extends RuntimeException {

    public ProductNotFoundException(UUID productId) {
        super("El producto " + productId + " no existe en el catálogo");
    }
}
