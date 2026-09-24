package com.pedidos360.orders.catalog;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

public interface CatalogClient {

    CatalogProduct getProduct(UUID productId);

    void reserveStock(UUID orderId, List<StockItem> items);

    void releaseStock(UUID orderId);

    record CatalogProduct(
            UUID id,
            String name,
            BigDecimal price,
            Integer stock,
            boolean active
    ) {
    }

    record StockItem(UUID productId, int quantity) {
    }
}
