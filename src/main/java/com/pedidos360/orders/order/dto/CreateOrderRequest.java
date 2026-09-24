package com.pedidos360.orders.order.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import java.util.List;

public record CreateOrderRequest(
        @NotEmpty(message = "El pedido debe contener al menos un ítem")
        @Size(max = 100, message = "Un pedido no puede contener más de 100 ítems")
        List<@Valid OrderItemRequest> items,
        @Size(max = 1000, message = "Las notas no pueden superar 1000 caracteres")
        String notes
) {

    public CreateOrderRequest {
        items = items == null ? null : List.copyOf(items);
    }
}
