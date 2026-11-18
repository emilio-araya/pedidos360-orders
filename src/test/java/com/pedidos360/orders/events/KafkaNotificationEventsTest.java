package com.pedidos360.orders.events;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pedidos360.orders.catalog.CatalogClient;
import com.pedidos360.orders.order.domain.OrderStatus;
import com.pedidos360.orders.order.dto.CreateGuestOrderRequest;
import com.pedidos360.orders.order.dto.CreateOrderRequest;
import com.pedidos360.orders.order.dto.GuestOrderResponse;
import com.pedidos360.orders.order.dto.OrderItemRequest;
import com.pedidos360.orders.order.dto.OrderResponse;
import com.pedidos360.orders.order.repository.OrderRepository;
import com.pedidos360.orders.order.service.GuestOrderService;
import com.pedidos360.orders.order.service.OrderService;
import com.pedidos360.orders.outbox.OutboxEventRepository;
import java.math.BigDecimal;
import java.util.Comparator;
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
 * Cada cambio de estado de un pedido de invitado encola un NOTIFICATION_REQUESTED
 * en la misma transacción; el consumidor lo enviaría por correo. Los pedidos con
 * cuenta no encolan (no hay correo del cliente). El pedido recién creado tampoco:
 * el voucher ya le dio la bienvenida.
 */
@SpringBootTest
@ActiveProfiles("local")
class KafkaNotificationEventsTest {

    private static final UUID PRODUCT_ID = UUID.fromString("abababab-abab-abab-abab-abababababab");

    @Autowired
    private OrderService orderService;

    @Autowired
    private GuestOrderService guestOrderService;

    @Autowired
    private OrderRepository orderRepository;

    @Autowired
    private OutboxEventRepository outboxRepository;

    @Autowired
    private KafkaOutboxEventRepository kafkaOutboxRepository;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private CatalogClient catalogClient;

    @BeforeEach
    void setUp() {
        kafkaOutboxRepository.deleteAll();
        outboxRepository.deleteAll();
        orderRepository.deleteAll();
        when(catalogClient.getProduct(PRODUCT_ID)).thenReturn(new CatalogClient.CatalogProduct(
                PRODUCT_ID, "Producto Kafka", new BigDecimal("1000.00"), 50, true));
    }

    @Test
    void aGuestOrderEnqueuesOneNotificationPerTransition() {
        GuestOrderResponse created = guestOrderService.create(new CreateGuestOrderRequest(
                "invitado@example.com", List.of(new OrderItemRequest(PRODUCT_ID, 2)), null));
        String orderId = kafkaOutboxRepository.findAll().get(0).getAggregateId();

        // La creación sólo encola ORDER_CREATED: el voucher ya avisó al invitado.
        assertThat(eventsOf(orderId)).extracting(KafkaOutboxEvent::getEventType)
                .containsExactly("ORDER_CREATED");

        orderService.changeStatus(orderId, OrderStatus.ACEPTADO, operator());

        List<KafkaOutboxEvent> events = eventsOf(orderId);
        assertThat(events).extracting(KafkaOutboxEvent::getEventType)
                .containsExactly("ORDER_CREATED", "ORDER_ACCEPTED", "NOTIFICATION_REQUESTED");
        KafkaOutboxEvent notification = events.get(2);
        assertThat(notification.getTargetTopic()).isEqualTo(KafkaTopics.NOTIFICATION_EVENTS);
        assertThat(notification.getEventType()).isEqualTo("NOTIFICATION_REQUESTED");
    }

    @Test
    void notificationPayloadCarriesWhatTheEmailNeeds() throws Exception {
        GuestOrderResponse created = guestOrderService.create(new CreateGuestOrderRequest(
                "invitado@example.com", List.of(new OrderItemRequest(PRODUCT_ID, 2)), null));
        String orderId = kafkaOutboxRepository.findAll().get(0).getAggregateId();

        orderService.changeStatus(orderId, OrderStatus.ACEPTADO, operator());
        // Confirmar el stock no cambia el estado: es un prerequisito para DESPACHO/PREPARACIÓN.
        orderService.applyStockReserved(java.util.UUID.fromString(orderId));
        orderService.changeStatus(orderId, OrderStatus.EN_PREPARACION, operator());

        List<KafkaOutboxEvent> events = eventsOf(orderId);
        assertThat(events).extracting(KafkaOutboxEvent::getEventType)
                .containsExactly("ORDER_CREATED", "ORDER_ACCEPTED", "NOTIFICATION_REQUESTED",
                        "ORDER_IN_PREPARATION", "NOTIFICATION_REQUESTED");

        JsonNode first = objectMapper.readTree(events.get(2).getPayload());
        assertThat(first.get("orderNumber").asText()).isNotBlank();
        assertThat(first.get("email").asText()).isEqualTo("invitado@example.com");
        assertThat(first.get("status").asText()).isEqualTo("ACEPTADO");
        assertThat(first.get("previousStatus").asText()).isEqualTo("CREADO");
        assertThat(first.get("orderId").asText()).isEqualTo(orderId);
        assertThat(first.hasNonNull("eventId")).isTrue();
        // Nunca el token crudo del invitado (sólo se persiste su hash).
        assertThat(first.has("guestToken")).isFalse();
        assertThat(first.has("guestTokenHash")).isFalse();

        JsonNode second = objectMapper.readTree(events.get(4).getPayload());
        assertThat(second.get("status").asText()).isEqualTo("EN_PREPARACION");
        assertThat(second.get("previousStatus").asText()).isEqualTo("ACEPTADO");
    }

    @Test
    void notificationRowsAreKeyedByOrderIdLikeTheDomainEvent() {
        GuestOrderResponse created = guestOrderService.create(new CreateGuestOrderRequest(
                "invitado@example.com", List.of(new OrderItemRequest(PRODUCT_ID, 2)), null));
        String orderId = kafkaOutboxRepository.findAll().get(0).getAggregateId();

        orderService.changeStatus(orderId, OrderStatus.ACEPTADO, operator());

        List<KafkaOutboxEvent> notifications = eventsOf(orderId).stream()
                .filter(event -> event.getTargetTopic().equals(KafkaTopics.NOTIFICATION_EVENTS))
                .toList();
        assertThat(notifications).hasSize(1);
        assertThat(notifications.get(0).getAggregateId()).isEqualTo(orderId);
    }

    @Test
    void accountOrdersDoNotEnqueueNotifications() {
        OrderResponse created = orderService.create(
                new CreateOrderRequest(List.of(new OrderItemRequest(PRODUCT_ID, 2)), null), operator());

        orderService.changeStatus(created.id(), OrderStatus.ACEPTADO, operator());

        assertThat(eventsOf(created.id())).extracting(KafkaOutboxEvent::getEventType)
                .containsExactly("ORDER_CREATED", "ORDER_ACCEPTED");
        assertThat(eventsOf(created.id())).noneMatch(event ->
                KafkaTopics.NOTIFICATION_EVENTS.equals(event.getTargetTopic()));
    }

    private List<KafkaOutboxEvent> eventsOf(String orderId) {
        return kafkaOutboxRepository.findAll().stream()
                .filter(event -> event.getAggregateId().equals(orderId))
                .sorted(Comparator.comparing(KafkaOutboxEvent::getCreatedAt))
                .toList();
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
