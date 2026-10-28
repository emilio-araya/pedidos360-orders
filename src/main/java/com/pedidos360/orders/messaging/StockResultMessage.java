package com.pedidos360.orders.messaging;

import java.util.UUID;

/**
 * Resultado de reserva que publica el catálogo. Contrato entre servicios: los
 * nombres de los campos y los valores de {@code outcome} deben coincidir con
 * `StockResultMessage` del catálogo.
 */
public record StockResultMessage(UUID orderId, String outcome, String errorCode, String reason) {

    public static final String RESERVED = "RESERVED";
    public static final String FAILED = "FAILED";
}
