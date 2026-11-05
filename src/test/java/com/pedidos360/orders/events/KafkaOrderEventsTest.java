package com.pedidos360.orders.events;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
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
import com.pedidos360.orders.order.dto.UpdateOrderRequest;
import com.pedidos360.orders.order.exception.InvalidOrderTransitionException;
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
 * Cada cambio de estado del pedido deja exactamente un evento en la outbox de
 * Kafka, con la clave del pedido y el estado anterior y nuevo. Un cambio
 * rechazado no deja ninguno.
 */
@SpringBootTest
@ActiveProfiles("local")
class KafkaOrderEventsTest {

    private static final UUID PRODUCT_ID = UUID.fromString("abababab-abab-abab-abab-abababababab");
    private static final UUID OTHER_PRODUCT_ID = UUID.fromString("cdcdcdcd-cdcd-cdcd-cdcd-cdcdcdcdcdcd");

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
        when(catalogClient.getProduct(OTHER_PRODUCT_ID)).thenReturn(new CatalogClient.CatalogProduct(
                OTHER_PRODUCT_ID, "Otro producto Kafka", new BigDecimal("2000.00"), 50, true));
    }

    @Test
    void creatingAnOrderRecordsOneOrderCreatedEvent() throws Exception {
        OrderResponse created = createOrder();

        KafkaOutboxEvent event = onlyEventOf(created.id());
        assertEvent(event, created.id(), "ORDER_CREATED", "CREADO", null);
        assertItems(event, PRODUCT_ID, 2);
        assertThat(event.getPublishedAt()).isNull();
        assertThat(event.getAttempts()).isZero();
    }

    @Test
    void updatingAnOrderRecordsOneOrderUpdatedEventWithTheNewItems() throws Exception {
        OrderResponse created = createOrder();

        orderService.update(
                created.id(),
                new UpdateOrderRequest(List.of(new OrderItemRequest(OTHER_PRODUCT_ID, 5)), null),
                operator());

        List<KafkaOutboxEvent> events = eventsOf(created.id());
        assertThat(events).extracting(KafkaOutboxEvent::getEventType)
                .containsExactly("ORDER_CREATED", "ORDER_UPDATED");
        assertEvent(events.get(1), created.id(), "ORDER_UPDATED", "CREADO", "CREADO");
        assertItems(events.get(1), OTHER_PRODUCT_ID, 5);
    }

    @Test
    void everyTransitionOfTheHappyPathRecordsExactlyOneEvent() throws Exception {
        OrderResponse created = createOrder();
        UUID orderId = UUID.fromString(created.id());

        orderService.changeStatus(created.id(), OrderStatus.ACEPTADO, operator());
        // Confirmar el stock no cambia el estado: no es un evento de dominio.
        orderService.applyStockReserved(orderId);
        orderService.changeStatus(created.id(), OrderStatus.EN_PREPARACION, operator());
        orderService.changeStatus(created.id(), OrderStatus.DESPACHADO, operator());
        orderService.changeStatus(created.id(), OrderStatus.ENTREGADO, operator());

        List<KafkaOutboxEvent> events = eventsOf(created.id());
        assertThat(events).extracting(KafkaOutboxEvent::getEventType).containsExactly(
                "ORDER_CREATED", "ORDER_ACCEPTED", "ORDER_IN_PREPARATION",
                "ORDER_DISPATCHED", "ORDER_DELIVERED");
        assertEvent(events.get(1), created.id(), "ORDER_ACCEPTED", "ACEPTADO", "CREADO");
        assertEvent(events.get(2), created.id(), "ORDER_IN_PREPARATION", "EN_PREPARACION", "ACEPTADO");
        assertEvent(events.get(3), created.id(), "ORDER_DISPATCHED", "DESPACHADO", "EN_PREPARACION");
        assertEvent(events.get(4), created.id(), "ORDER_DELIVERED", "ENTREGADO", "DESPACHADO");
        for (KafkaOutboxEvent event : events) {
            assertItems(event, PRODUCT_ID, 2);
        }
    }

    @Test
    void cancellingThroughAStatusChangeRecordsOneCancelledEventWithThePreviousStatus() throws Exception {
        OrderResponse created = createOrder();
        orderService.changeStatus(created.id(), OrderStatus.ACEPTADO, operator());

        orderService.changeStatus(created.id(), OrderStatus.CANCELADO, operator());

        List<KafkaOutboxEvent> events = eventsOf(created.id());
        assertThat(events).hasSize(3);
        assertEvent(events.get(2), created.id(), "ORDER_CANCELLED", "CANCELADO", "ACEPTADO");
        assertItems(events.get(2), PRODUCT_ID, 2);
    }

    @Test
    void deletingAnOrderRecordsOneCancelledEventFromCreado() throws Exception {
        OrderResponse created = createOrder();

        orderService.delete(created.id(), operator());

        List<KafkaOutboxEvent> events = eventsOf(created.id());
        assertThat(events).hasSize(2);
        assertEvent(events.get(1), created.id(), "ORDER_CANCELLED", "CANCELADO", "CREADO");
    }

    @Test
    void aStockRejectionRecordsOneCancelledEventAndADuplicateRecordsNothing() throws Exception {
        OrderResponse created = createOrder();
        orderService.changeStatus(created.id(), OrderStatus.ACEPTADO, operator());
        UUID orderId = UUID.fromString(created.id());

        orderService.applyStockRejected(orderId, "INSUFFICIENT_STOCK", "Stock insuficiente");
        orderService.applyStockRejected(orderId, "INSUFFICIENT_STOCK", "Stock insuficiente");

        List<KafkaOutboxEvent> events = eventsOf(created.id());
        assertThat(events).extracting(KafkaOutboxEvent::getEventType)
                .containsExactly("ORDER_CREATED", "ORDER_ACCEPTED", "ORDER_CANCELLED");
        assertEvent(events.get(2), created.id(), "ORDER_CANCELLED", "CANCELADO", "ACEPTADO");
    }

    @Test
    void guestCreationAndCancellationRecordOneEventEach() throws Exception {
        GuestOrderResponse created = guestOrderService.create(new CreateGuestOrderRequest(
                "invitado@example.com", List.of(new OrderItemRequest(PRODUCT_ID, 2)), null));
        String orderId = kafkaOutboxRepository.findAll().get(0).getAggregateId();

        guestOrderService.cancel(created.trackingToken());

        List<KafkaOutboxEvent> events = eventsOf(orderId);
        assertThat(events).hasSize(2);
        assertEvent(events.get(0), orderId, "ORDER_CREATED", "CREADO", null);
        assertEvent(events.get(1), orderId, "ORDER_CANCELLED", "CANCELADO", "CREADO");
        assertItems(events.get(1), PRODUCT_ID, 2);
    }

    @Test
    void aRejectedTransitionRecordsNoEvent() {
        OrderResponse created = createOrder();

        assertThatThrownBy(() -> orderService.changeStatus(created.id(), OrderStatus.ENTREGADO, operator()))
                .isInstanceOf(InvalidOrderTransitionException.class);

        assertThat(eventsOf(created.id())).extracting(KafkaOutboxEvent::getEventType)
                .containsExactly("ORDER_CREATED");
    }

    private void assertEvent(
            KafkaOutboxEvent event,
            String orderId,
            String eventType,
            String status,
            String previousStatus
    ) throws Exception {
        JsonNode payload = objectMapper.readTree(event.getPayload());
        assertThat(event.getAggregateId()).isEqualTo(orderId);
        assertThat(event.getEventType()).isEqualTo(eventType);
        assertThat(payload.get("eventId").asText()).isEqualTo(event.getId());
        assertThat(payload.get("eventType").asText()).isEqualTo(eventType);
        assertThat(payload.get("orderId").asText()).isEqualTo(orderId);
        assertThat(payload.get("status").asText()).isEqualTo(status);
        if (previousStatus == null) {
            assertThat(payload.get("previousStatus").isNull()).isTrue();
        } else {
            assertThat(payload.get("previousStatus").asText()).isEqualTo(previousStatus);
        }
    }

    private void assertItems(KafkaOutboxEvent event, UUID productId, int quantity) throws Exception {
        JsonNode items = objectMapper.readTree(event.getPayload()).get("items");
        assertThat(items).hasSize(1);
        assertThat(items.get(0).get("productId").asText()).isEqualTo(productId.toString());
        assertThat(items.get(0).get("quantity").asInt()).isEqualTo(quantity);
    }

    private KafkaOutboxEvent onlyEventOf(String orderId) {
        List<KafkaOutboxEvent> events = eventsOf(orderId);
        assertThat(events).hasSize(1);
        return events.get(0);
    }

    private List<KafkaOutboxEvent> eventsOf(String orderId) {
        return kafkaOutboxRepository.findAll().stream()
                .filter(event -> event.getAggregateId().equals(orderId))
                .sorted(Comparator.comparing(KafkaOutboxEvent::getCreatedAt))
                .toList();
    }

    private OrderResponse createOrder() {
        return orderService.create(
                new CreateOrderRequest(List.of(new OrderItemRequest(PRODUCT_ID, 2)), null), operator());
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
