package com.pedidos360.orders.messaging;

import com.pedidos360.orders.order.exception.OrderNotFoundException;
import com.pedidos360.orders.order.service.OrderService;
import org.springframework.stereotype.Component;

/**
 * Traduce el resultado de la reserva a una operación del pedido. Las
 * operaciones de {@link OrderService} son idempotentes, así que una reentrega
 * del mismo resultado no tiene efecto.
 */
@Component
public class StockResultListener {

    private final OrderService orderService;

    public StockResultListener(OrderService orderService) {
        this.orderService = orderService;
    }

    public void onStockResult(StockResultMessage message) {
        if (message.orderId() == null) {
            throw new PoisonMessageException("Resultado de reserva sin orderId");
        }
        try {
            switch (message.outcome() == null ? "" : message.outcome()) {
                case StockResultMessage.RESERVED -> orderService.applyStockReserved(message.orderId());
                case StockResultMessage.FAILED -> orderService.applyStockRejected(
                        message.orderId(), message.errorCode(), message.reason());
                default -> throw new PoisonMessageException(
                        "Resultado de reserva desconocido: " + message.outcome());
            }
        } catch (OrderNotFoundException exception) {
            throw new PoisonMessageException("Resultado de reserva para un pedido inexistente", exception);
        }
    }
}
