package com.pedidos360.orders.messaging;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.pedidos360.orders.order.exception.OrderNotFoundException;
import com.pedidos360.orders.order.service.OrderService;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;

/**
 * El listener traduce el resultado del catálogo a una operación del pedido.
 * Un mensaje que nunca podrá procesarse es veneno y va directo a la DLQ; un
 * fallo transitorio se propaga para reintentarlo.
 */
class StockResultListenerTest {

    private static final UUID ORDER_ID = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");

    private OrderService orderService;
    private StockResultListener listener;

    @BeforeEach
    void setUp() {
        orderService = mock(OrderService.class);
        listener = new StockResultListener(orderService);
    }

    @Test
    void appliesAConfirmedReservation() {
        listener.onStockResult(new StockResultMessage(ORDER_ID, StockResultMessage.RESERVED, null, null));

        verify(orderService).applyStockReserved(ORDER_ID);
        verify(orderService, never()).applyStockRejected(any(), any(), any());
    }

    @Test
    void appliesARejectedReservationWithItsReason() {
        listener.onStockResult(new StockResultMessage(
                ORDER_ID, StockResultMessage.FAILED, "INSUFFICIENT_STOCK", "Stock insuficiente"));

        verify(orderService).applyStockRejected(ORDER_ID, "INSUFFICIENT_STOCK", "Stock insuficiente");
    }

    @Test
    void treatsAnUnknownOutcomeAsPoison() {
        assertThatThrownBy(() -> listener.onStockResult(new StockResultMessage(ORDER_ID, "MAYBE", null, null)))
                .isInstanceOf(PoisonMessageException.class);
        verifyNoInteractions(orderService);
    }

    @Test
    void treatsAMissingOrderIdAsPoison() {
        assertThatThrownBy(() -> listener.onStockResult(
                new StockResultMessage(null, StockResultMessage.RESERVED, null, null)))
                .isInstanceOf(PoisonMessageException.class);
        verifyNoInteractions(orderService);
    }

    @Test
    void treatsAResultForAnUnknownOrderAsPoison() {
        doThrow(new OrderNotFoundException(ORDER_ID.toString())).when(orderService).applyStockReserved(ORDER_ID);

        assertThatThrownBy(() -> listener.onStockResult(
                new StockResultMessage(ORDER_ID, StockResultMessage.RESERVED, null, null)))
                .isInstanceOf(PoisonMessageException.class);
    }

    @Test
    void propagatesATransientFailure() {
        doThrow(new DataAccessResourceFailureException("base caída")).when(orderService).applyStockReserved(ORDER_ID);

        assertThatThrownBy(() -> listener.onStockResult(
                new StockResultMessage(ORDER_ID, StockResultMessage.RESERVED, null, null)))
                .isInstanceOf(DataAccessResourceFailureException.class);
    }
}
