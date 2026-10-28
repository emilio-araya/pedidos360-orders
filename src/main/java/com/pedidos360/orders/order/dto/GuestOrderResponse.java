package com.pedidos360.orders.order.dto;

import com.pedidos360.orders.order.domain.Order;
import com.pedidos360.orders.order.domain.OrderStatus;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * What a guest sees of an order. It never exposes the internal id, the owner,
 * the stock flag or the full email. {@code trackingToken} is only set in the
 * creation response; elsewhere it is null and omitted from the JSON.
 */
public record GuestOrderResponse(
        String orderNumber,
        OrderStatus status,
        List<OrderItemResponse> items,
        BigDecimal total,
        String notes,
        String cancellationReason,
        String maskedEmail,
        Instant createdAt,
        Instant updatedAt,
        String trackingToken
) {

    public GuestOrderResponse {
        items = List.copyOf(items);
    }

    public static GuestOrderResponse from(Order order) {
        return from(order, null);
    }

    public static GuestOrderResponse from(Order order, String trackingToken) {
        List<OrderItemResponse> items = order.getItems().stream()
                .map(item -> new OrderItemResponse(
                        item.getProductId(),
                        item.getProductName(),
                        item.getQuantity(),
                        item.getUnitPrice(),
                        item.getSubtotal()
                ))
                .toList();
        return new GuestOrderResponse(
                order.getOrderNumber(),
                order.getStatus(),
                items,
                order.getTotal(),
                order.getNotes(),
                order.getCancellationReason(),
                GuestEmails.mask(order.getCustomerEmail()),
                order.getCreatedAt(),
                order.getUpdatedAt(),
                trackingToken
        );
    }

    @Override
    public String toString() {
        // Keeps the token out of any accidental log of this response.
        return "GuestOrderResponse[orderNumber=" + orderNumber + ", status=" + status + "]";
    }
}
