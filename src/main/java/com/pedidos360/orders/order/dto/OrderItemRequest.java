package com.pedidos360.orders.order.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.util.UUID;

public record OrderItemRequest(
        @NotNull(message = "productId es obligatorio")
        UUID productId,
        @NotNull(message = "quantity es obligatoria")
        @Min(value = 1, message = "quantity debe ser mayor o igual a 1")
        @Max(value = 999, message = "quantity no puede superar 999")
        Integer quantity
) {
}
