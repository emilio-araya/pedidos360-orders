package com.pedidos360.orders.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.pedidos360.orders.catalog.CatalogClient;
import com.pedidos360.orders.order.domain.OrderStatus;
import com.pedidos360.orders.order.dto.CreateOrderRequest;
import com.pedidos360.orders.order.dto.OrderItemRequest;
import com.pedidos360.orders.order.dto.OrderResponse;
import com.pedidos360.orders.order.repository.OrderRepository;
import com.pedidos360.orders.order.service.OrderService;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
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
 * Verifica que cada transición relevante deje un evento en la tabla de outbox,
 * dentro de la misma transacción que cambia el estado del pedido.
 */
@SpringBootTest
@ActiveProfiles("local")
class OrderOutboxTest {

    private static final UUID PRODUCT_ID = UUID.fromString("dddddddd-dddd-dddd-dddd-dddddddddddd");

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
        when(catalogClient.getProduct(PRODUCT_ID)).thenReturn(new CatalogClient.CatalogProduct(
                PRODUCT_ID,
                "Producto de outbox",
                new BigDecimal("5000.00"),
                10,
                true
        ));
    }

    @Test
    void acceptingAnOrderRecordsOneAcceptedEvent() {
        OrderResponse created = createOrder();

        orderService.changeStatus(created.id(), OrderStatus.ACEPTADO, operator());

        List<OutboxEvent> pending = outboxRepository.findAll();
        assertThat(pending).hasSize(1);
        OutboxEvent event = pending.get(0);
        assertThat(event.getEventType()).isEqualTo("pedido.aceptado");
        assertThat(event.getAggregateId()).isEqualTo(created.id());
        assertThat(event.getPublishedAt()).isNull();
        assertThat(event.getAttempts()).isZero();
        assertThat(event.getPayload())
                .contains(created.id())
                .contains(PRODUCT_ID.toString())
                .contains("\"quantity\":3");
    }

    @Test
    void cancellingAnOrderRecordsOneCancelledEvent() {
        OrderResponse created = createOrder();

        orderService.changeStatus(created.id(), OrderStatus.CANCELADO, operator());

        List<OutboxEvent> pending = outboxRepository.findAll();
        assertThat(pending).hasSize(1);
        assertThat(pending.get(0).getEventType()).isEqualTo("pedido.cancelado");
        assertThat(pending.get(0).getAggregateId()).isEqualTo(created.id());
        assertThat(pending.get(0).getPayload()).contains(created.id());
    }

    @Test
    void deletingAnOrderRecordsOneCancelledEvent() {
        OrderResponse created = createOrder();

        orderService.delete(created.id(), operator());

        assertThat(outboxRepository.findAll())
                .singleElement()
                .satisfies(event -> assertThat(event.getEventType()).isEqualTo("pedido.cancelado"));
    }

    private OrderResponse createOrder() {
        return orderService.create(
                new CreateOrderRequest(List.of(new OrderItemRequest(PRODUCT_ID, 3)), null),
                operator()
        );
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
