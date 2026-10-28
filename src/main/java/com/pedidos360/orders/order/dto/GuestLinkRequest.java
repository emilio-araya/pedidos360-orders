package com.pedidos360.orders.order.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.Locale;

/** Ask for a new tracking link without revealing whether the order exists. */
public record GuestLinkRequest(
        @NotBlank(message = "El correo es obligatorio")
        @Email(message = "El correo no tiene un formato válido")
        @Size(max = 254, message = "El correo no puede superar 254 caracteres")
        String email,
        @NotBlank(message = "El número de pedido es obligatorio")
        @Pattern(regexp = "^PED-[0-9A-F]{8}$", message = "El número de pedido no tiene un formato válido")
        String orderNumber
) {

    public GuestLinkRequest {
        email = GuestEmails.normalize(email);
        orderNumber = orderNumber == null ? null : orderNumber.trim().toUpperCase(Locale.ROOT);
    }
}
