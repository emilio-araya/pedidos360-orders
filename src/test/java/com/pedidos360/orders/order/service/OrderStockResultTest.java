package com.pedidos360.orders.order.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import com.pedidos360.orders.catalog.CatalogClient;
import com.pedidos360.orders.order.domain.OrderStatus;
import com.pedidos360.orders.order.dto.CreateOrderRequest;
import com.pedidos360.orders.order.dto.OrderItemRequest;
import com.pedidos360.orders.order.dto.OrderResponse;
import com.pedidos360.orders.order.exception.OrderBusinessException;
import com.pedidos360.orders.order.exception.OrderNotFoundException;
import com.pedidos360.orders.order.repository.OrderRepository;
import com.pedidos360.orders.outbox.OutboxEvent;
import com.pedidos360.orders.outbox.OutboxEventRepository;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * La reserva de stock es asíncrona: aceptar no llama al catálogo y el pedido
 * queda con `stockReserved=false` hasta que llega el resultado. Cada resultado
 * se aplica de forma idempotente, y uno tardío nunca deja stock inmovilizado.
 */
@SpringBootTest
@ActiveProfiles("local")
class OrderStockResultTest {

    private static final UUID PRODUCT_ID = UUID.fromString("eeeeeeee-eeee-eeee-eeee-eeeeeeeeeeee");

    @Autowired
    private OrderService orderService;

    @Autowired
    private OrderRepository orderRepository;

    @Autowired
    private OutboxEventRepository outboxRepository;

    @MockitoBean
    private CatalogClient catalogClient;

    @BeforeEach
    void setUp() {
        outboxRepository.deleteAll();
        orderRepository.deleteAll();
        reset(catalogClient);
        when(catalogClient.getProduct(PRODUCT_ID)).thenReturn(new CatalogClient.CatalogProduct(
                PRODUCT_ID, "Producto asíncrono", new BigDecimal("3000.00"), 10, true));
    }

    @Test
    void acceptingDoesNotCallTheCatalogAndLeavesTheStockPending() {
        OrderResponse accepted = accept(createOrder());

        assertThat(accepted.status()).isEqualTo(OrderStatus.ACEPTADO);
        assertThat(accepted.stockReserved()).isFalse();
        verify(catalogClient).getProduct(PRODUCT_ID);
        verifyNoMoreInteractions(catalogClient);
    }

    @Test
    void aConfirmationMarksTheStockReservedAndADuplicateChangesNothing() {
        OrderResponse accepted = accept(createOrder());

        orderService.applyStockReserved(UUID.fromString(accepted.id()));
        orderService.applyStockReserved(UUID.fromString(accepted.id()));

        OrderResponse reloaded = orderService.get(accepted.id(), operator());
        assertThat(reloaded.status()).isEqualTo(OrderStatus.ACEPTADO);
        assertThat(reloaded.stockReserved()).isTrue();
        assertThat(eventTypes(accepted.id())).containsExactly("pedido.aceptado");
    }

    @Test
    void preparationRequiresTheReservationToBeConfirmed() {
        OrderResponse accepted = accept(createOrder());

        assertThatThrownBy(() -> orderService.changeStatus(accepted.id(), OrderStatus.EN_PREPARACION, operator()))
                .isInstanceOf(OrderBusinessException.class)
                .hasMessage("El stock del pedido todavía no está confirmado");

        orderService.applyStockReserved(UUID.fromString(accepted.id()));
        OrderResponse preparing = orderService.changeStatus(accepted.id(), OrderStatus.EN_PREPARACION, operator());
        assertThat(preparing.status()).isEqualTo(OrderStatus.EN_PREPARACION);
    }

    @Test
    void aRejectionCancelsTheOrderRecordsTheReasonAndADuplicateChangesNothing() {
        OrderResponse accepted = accept(createOrder());

        orderService.applyStockRejected(UUID.fromString(accepted.id()), "INSUFFICIENT_STOCK", "Stock insuficiente");
        orderService.applyStockRejected(UUID.fromString(accepted.id()), "INSUFFICIENT_STOCK", "Stock insuficiente");

        OrderResponse reloaded = orderService.get(accepted.id(), operator());
        assertThat(reloaded.status()).isEqualTo(OrderStatus.CANCELADO);
        assertThat(reloaded.stockReserved()).isFalse();
        assertThat(reloaded.cancellationReason()).isEqualTo("Stock insuficiente");
        assertThat(eventTypes(accepted.id())).containsExactly("pedido.aceptado", "pedido.cancelado");
    }

    @Test
    void aRejectionNeverCancelsAnOrderWhoseStockIsAlreadyReserved() {
        OrderResponse accepted = accept(createOrder());
        orderService.applyStockReserved(UUID.fromString(accepted.id()));

        orderService.applyStockRejected(UUID.fromString(accepted.id()), "CONFLICT", "Reserva distinta");

        OrderResponse reloaded = orderService.get(accepted.id(), operator());
        assertThat(reloaded.status()).isEqualTo(OrderStatus.ACEPTADO);
        assertThat(reloaded.stockReserved()).isTrue();
        assertThat(reloaded.cancellationReason()).isNull();
    }

    @Test
    void aLateConfirmationForACancelledOrderRequestsTheReleaseOnce() {
        OrderResponse accepted = accept(createOrder());
        orderService.changeStatus(accepted.id(), OrderStatus.CANCELADO, operator());

        orderService.applyStockReserved(UUID.fromString(accepted.id()));
        orderService.applyStockReserved(UUID.fromString(accepted.id()));

        OrderResponse reloaded = orderService.get(accepted.id(), operator());
        assertThat(reloaded.status()).isEqualTo(OrderStatus.CANCELADO);
        assertThat(reloaded.stockReserved()).isFalse();
        assertThat(eventTypes(accepted.id()))
                .containsExactly("pedido.aceptado", "pedido.cancelado", "pedido.reserva-huerfana");
    }


    @Test
    void concurrentCancellationAndStockConfirmationNeverLeaveReservationOnCancelledOrder() throws Exception {
        OrderResponse accepted = accept(createOrder());
        UUID orderId = UUID.fromString(accepted.id());
        Authentication operator = operator();
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);

        try {
            Future<?> cancellation = executor.submit(() -> {
                ready.countDown();
                awaitStart(start);
                orderService.changeStatus(accepted.id(), OrderStatus.CANCELADO, operator);
            });
            Future<?> confirmation = executor.submit(() -> {
                ready.countDown();
                awaitStart(start);
                orderService.applyStockReserved(orderId);
            });

            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            cancellation.get(10, TimeUnit.SECONDS);
            confirmation.get(10, TimeUnit.SECONDS);
        } finally {
            executor.shutdownNow();
        }

        OrderResponse reloaded = orderService.get(accepted.id(), operator);
        List<String> events = eventTypes(accepted.id());

        // Gane quien gane la carrera, cancelar debe ser el estado final y el
        // catálogo debe recibir una orden de liberación, sin duplicar cancelaciones.
        assertThat(reloaded.status()).isEqualTo(OrderStatus.CANCELADO);
        assertThat(reloaded.stockReserved()).isFalse();
        assertThat(events).contains("pedido.aceptado", "pedido.cancelado");
        assertThat(events.stream().filter("pedido.cancelado"::equals)).hasSize(1);
        assertThat(events.stream().filter("pedido.reserva-huerfana"::equals)).hasSizeLessThanOrEqualTo(1);
    }

    private void awaitStart(CountDownLatch start) {
        try {
            if (!start.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Timeout esperando el inicio de la operación concurrente");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Operación concurrente interrumpida", exception);
        }
    }

    @Test
    void aRejectionForACancelledOrderChangesNothing() {
        OrderResponse accepted = accept(createOrder());
        orderService.changeStatus(accepted.id(), OrderStatus.CANCELADO, operator());

        orderService.applyStockRejected(UUID.fromString(accepted.id()), "INSUFFICIENT_STOCK", "Stock insuficiente");

        OrderResponse reloaded = orderService.get(accepted.id(), operator());
        assertThat(reloaded.cancellationReason()).isNull();
        assertThat(eventTypes(accepted.id())).containsExactly("pedido.aceptado", "pedido.cancelado");
    }

    @Test
    void aResultForAnUnknownOrderIsRejected() {
        UUID unknown = UUID.randomUUID();

        assertThatThrownBy(() -> orderService.applyStockReserved(unknown))
                .isInstanceOf(OrderNotFoundException.class);
        assertThatThrownBy(() -> orderService.applyStockRejected(unknown, "X", "Y"))
                .isInstanceOf(OrderNotFoundException.class);
    }

    private List<String> eventTypes(String orderId) {
        return outboxRepository.findAll().stream()
                .filter(event -> event.getAggregateId().equals(orderId))
                .sorted((left, right) -> left.getCreatedAt().compareTo(right.getCreatedAt()))
                .map(OutboxEvent::getEventType)
                .toList();
    }

    private OrderResponse createOrder() {
        return orderService.create(
                new CreateOrderRequest(List.of(new OrderItemRequest(PRODUCT_ID, 2)), null), operator());
    }

    private OrderResponse accept(OrderResponse created) {
        return orderService.changeStatus(created.id(), OrderStatus.ACEPTADO, operator());
    }

    private Authentication operator() {
        Jwt jwt = Jwt.withTokenValue("operator-token")
                .header("alg", "none")
                .claim("oid", "operator-id")
                .claim("roles", List.of("Operador"))
                .build();
        return new JwtAuthenticationToken(jwt, List.of(new SimpleGrantedAuthority("ROLE_Operador")));
    }
}
