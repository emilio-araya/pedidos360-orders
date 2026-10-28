package com.pedidos360.orders.order.service;

import com.pedidos360.orders.catalog.CatalogClient;
import com.pedidos360.orders.order.domain.OrderItem;
import com.pedidos360.orders.order.dto.OrderItemRequest;
import com.pedidos360.orders.order.exception.OrderBusinessException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Builds order items from the catalog. Name and price always come from the
 * catalog on the server, never from the client, for both logged-in and guest
 * orders.
 */
@Component
class OrderItemSnapshotter {

    private final CatalogClient catalogClient;

    OrderItemSnapshotter(CatalogClient catalogClient) {
        this.catalogClient = catalogClient;
    }

    List<OrderItem> snapshot(List<OrderItemRequest> requests) {
        Set<UUID> uniqueProducts = new HashSet<>();
        List<OrderItem> items = new ArrayList<>(requests.size());
        for (OrderItemRequest request : requests) {
            if (!uniqueProducts.add(request.productId())) {
                throw new OrderBusinessException("No se puede repetir el producto " + request.productId());
            }
            CatalogClient.CatalogProduct product = catalogClient.getProduct(request.productId());
            if (product == null) {
                throw new OrderBusinessException("El catálogo no devolvió el producto " + request.productId());
            }
            if (!request.productId().equals(product.id())) {
                throw new OrderBusinessException("El catálogo devolvió un producto distinto al solicitado");
            }
            if (!product.active()) {
                throw new OrderBusinessException("El producto " + request.productId() + " no está activo");
            }
            if (product.name() == null || product.name().isBlank()) {
                throw new OrderBusinessException("El producto " + request.productId() + " no tiene un nombre válido");
            }
            if (product.name().trim().length() > 300) {
                throw new OrderBusinessException("El nombre del producto " + request.productId() + " es demasiado largo");
            }
            if (product.price() == null || product.price().compareTo(BigDecimal.ZERO) <= 0) {
                throw new OrderBusinessException("El producto " + request.productId() + " no tiene un precio válido");
            }
            BigDecimal normalizedPrice = product.price().setScale(2, RoundingMode.HALF_UP);
            if (normalizedPrice.compareTo(BigDecimal.ZERO) <= 0) {
                throw new OrderBusinessException("El precio del producto " + request.productId() + " es inválido");
            }
            if (normalizedPrice.precision() - normalizedPrice.scale() > 17) {
                throw new OrderBusinessException("El precio del producto " + request.productId() + " excede el máximo");
            }
            items.add(new OrderItem(
                    product.id().toString(),
                    product.name().trim(),
                    request.quantity(),
                    normalizedPrice
            ));
        }
        BigDecimal total = items.stream()
                .map(OrderItem::getSubtotal)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        if (total.precision() - total.scale() > 17) {
            throw new OrderBusinessException("El total del pedido excede el máximo permitido");
        }
        return items;
    }
}
