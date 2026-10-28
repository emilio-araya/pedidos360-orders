package com.pedidos360.orders.order.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import java.util.List;

/**
 * Guest checkout payload. The email is only used to send the voucher and the
 * tracking link; it is normalized (trimmed, lowercase) before validation.
 */
public record CreateGuestOrderRequest(
        @NotBlank(message = "El correo es obligatorio")
        @Email(message = "El correo no tiene un formato válido")
        @Size(max = 254, message = "El correo no puede superar 254 caracteres")
        String email,
        @NotEmpty(message = "El pedido debe contener al menos un ítem")
        @Size(max = 100, message = "Un pedido no puede contener más de 100 ítems")
        List<@Valid OrderItemRequest> items,
        @Size(max = 1000, message = "Las notas no pueden superar 1000 caracteres")
        String notes
) {

    public CreateGuestOrderRequest {
        email = GuestEmails.normalize(email);
        items = items == null ? null : List.copyOf(items);
    }
}
