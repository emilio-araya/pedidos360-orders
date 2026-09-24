package com.pedidos360.orders.order.dto;

import com.pedidos360.orders.order.domain.OrderStatus;
import jakarta.validation.constraints.NotNull;

public record ChangeOrderStatusRequest(
        @NotNull(message = "status es obligatorio")
        OrderStatus status
) {
}
