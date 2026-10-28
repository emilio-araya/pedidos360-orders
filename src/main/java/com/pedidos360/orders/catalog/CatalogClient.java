package com.pedidos360.orders.catalog;

import java.math.BigDecimal;
import java.util.UUID;

public interface CatalogClient {

    CatalogProduct getProduct(UUID productId);

    record CatalogProduct(
            UUID id,
            String name,
            BigDecimal price,
            Integer stock,
            boolean active
    ) {
    }
}
