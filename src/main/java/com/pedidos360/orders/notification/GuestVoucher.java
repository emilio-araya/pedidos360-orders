package com.pedidos360.orders.notification;

import java.math.BigDecimal;
import java.util.List;

/** What the voucher email needs, copied out of the order before the commit ends. */
public record GuestVoucher(
        String to,
        String orderNumber,
        String status,
        BigDecimal total,
        List<Item> items,
        String trackingLink
) {

    public GuestVoucher {
        items = List.copyOf(items);
    }

    public record Item(String productName, int quantity, BigDecimal unitPrice, BigDecimal subtotal) {
    }

    /** Copies the order into an immutable payload, safe to use after the commit. */
    public static GuestVoucher from(com.pedidos360.orders.order.domain.Order order, String trackingLink) {
        List<Item> items = order.getItems().stream()
                .map(item -> new Item(
                        item.getProductName(),
                        item.getQuantity(),
                        item.getUnitPrice(),
                        item.getSubtotal()))
                .toList();
        return new GuestVoucher(
                order.getCustomerEmail(),
                order.getOrderNumber(),
                order.getStatus().name(),
                order.getTotal(),
                items,
                trackingLink);
    }
}
