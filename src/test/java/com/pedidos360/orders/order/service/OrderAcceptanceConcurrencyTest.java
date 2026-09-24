package com.pedidos360.orders.order.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.pedidos360.orders.catalog.CatalogClient;
import com.pedidos360.orders.order.domain.OrderStatus;
import com.pedidos360.orders.order.dto.CreateOrderRequest;
import com.pedidos360.orders.order.dto.OrderItemRequest;
import com.pedidos360.orders.order.dto.OrderResponse;
import com.pedidos360.orders.order.repository.OrderRepository;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
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

@SpringBootTest
@ActiveProfiles("local")
class OrderAcceptanceConcurrencyTest {

    private static final UUID PRODUCT_ID = UUID.fromString("cccccccc-cccc-cccc-cccc-cccccccccccc");

    @Autowired
    private OrderService orderService;

    @Autowired
    private OrderRepository orderRepository;

    @MockitoBean
    private CatalogClient catalogClient;

    private ExecutorService executor;

    @BeforeEach
    void setUp() {
        orderRepository.deleteAll();
        reset(catalogClient);
        executor = Executors.newFixedThreadPool(2);
        when(catalogClient.getProduct(PRODUCT_ID)).thenReturn(new CatalogClient.CatalogProduct(
                PRODUCT_ID,
                "Producto concurrente",
                new BigDecimal("1000.00"),
                20,
                true
        ));
    }

    @AfterEach
    void tearDown() {
        executor.shutdownNow();
    }

    @Test
    void concurrentAcceptanceCallsReservationOnlyOnce() throws Exception {
        Authentication operator = operator();
        CreateOrderRequest request = new CreateOrderRequest(
                List.of(new OrderItemRequest(PRODUCT_ID, 2)),
                null
        );
        OrderResponse created = orderService.create(request, operator);

        CountDownLatch reservationEntered = new CountDownLatch(1);
        CountDownLatch allowReservationToFinish = new CountDownLatch(1);
        doAnswer(invocation -> {
            reservationEntered.countDown();
            if (!allowReservationToFinish.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Timeout esperando para finalizar la reserva");
            }
            return null;
        }).when(catalogClient).reserveStock(any(), any());

        Future<String> first = executor.submit(() -> accept(created.id(), operator));
        assertThat(reservationEntered.await(5, TimeUnit.SECONDS)).isTrue();
        Future<String> second = executor.submit(() -> accept(created.id(), operator));
        allowReservationToFinish.countDown();

        assertThat(List.of(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS)))
                .containsExactlyInAnyOrder("ACEPTADO", "CONFLICTO");
        OrderResponse persisted = orderService.get(created.id(), operator);
        assertThat(persisted.status()).isEqualTo(OrderStatus.ACEPTADO);
        assertThat(persisted.stockReserved()).isTrue();
        verify(catalogClient, times(1)).reserveStock(any(), any());
    }

    private String accept(String id, Authentication authentication) {
        try {
            orderService.changeStatus(id, OrderStatus.ACEPTADO, authentication);
            return "ACEPTADO";
        } catch (com.pedidos360.orders.order.exception.InvalidOrderTransitionException exception) {
            return "CONFLICTO";
        }
    }

    private Authentication operator() {
        Jwt jwt = Jwt.withTokenValue("operator-token")
                .header("alg", "none")
                .claim("oid", "operator-id")
                .claim("roles", List.of("Operador"))
                .build();
        return new JwtAuthenticationToken(
                jwt,
                List.of(new SimpleGrantedAuthority("ROLE_Operador"))
        );
    }
}
