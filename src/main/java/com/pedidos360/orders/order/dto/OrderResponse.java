package com.pedidos360.orders.order.dto;

import com.pedidos360.orders.order.domain.Order;
import com.pedidos360.orders.order.domain.OrderStatus;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public record OrderResponse(
        String id,
        String orderNumber,
        String customerId,
        // Email of a guest order; null (omitted) for orders placed with login.
        String customerEmail,
        OrderStatus status,
        List<OrderItemResponse> items,
        BigDecimal total,
        String notes,
        boolean stockReserved,
        String cancellationReason,
        Instant createdAt,
        Instant updatedAt
) {

    public OrderResponse {
        items = List.copyOf(items);
    }

    public static OrderResponse from(Order order) {
        List<OrderItemResponse> items = order.getItems().stream()
                .map(item -> new OrderItemResponse(
                        item.getProductId(),
                        item.getProductName(),
                        item.getQuantity(),
                        item.getUnitPrice(),
                        item.getSubtotal()
                ))
                .toList();
        return new OrderResponse(
                order.getId(),
                order.getOrderNumber(),
                order.getCustomerId(),
                order.getCustomerEmail(),
                order.getStatus(),
                items,
                order.getTotal(),
                order.getNotes(),
                order.isStockReserved(),
                order.getCancellationReason(),
                order.getCreatedAt(),
                order.getUpdatedAt()
        );
    }
}
