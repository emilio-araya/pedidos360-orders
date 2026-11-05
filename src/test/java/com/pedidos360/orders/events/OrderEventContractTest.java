package com.pedidos360.orders.events;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pedidos360.orders.catalog.CatalogClient;
import com.pedidos360.orders.order.dto.CreateOrderRequest;
import com.pedidos360.orders.order.dto.OrderItemRequest;
import com.pedidos360.orders.order.dto.OrderResponse;
import com.pedidos360.orders.order.repository.OrderRepository;
import com.pedidos360.orders.order.service.OrderService;
import com.pedidos360.orders.outbox.OutboxEventRepository;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
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
 * Contrato S2 del valor publicado en `pedidos360.order-events`: el catálogo lo
 * deserializa campo por campo, así que cualquier diferencia aquí lo rompe.
 */
@SpringBootTest
@ActiveProfiles("local")
class OrderEventContractTest {

    private static final UUID FIRST_PRODUCT = UUID.fromString("12121212-1212-1212-1212-121212121212");
    private static final UUID SECOND_PRODUCT = UUID.fromString("34343434-3434-3434-3434-343434343434");

    @Autowired
    private OrderService orderService;

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
        when(catalogClient.getProduct(FIRST_PRODUCT)).thenReturn(new CatalogClient.CatalogProduct(
                FIRST_PRODUCT, "Primero", new BigDecimal("100.00"), 50, true));
        when(catalogClient.getProduct(SECOND_PRODUCT)).thenReturn(new CatalogClient.CatalogProduct(
                SECOND_PRODUCT, "Segundo", new BigDecimal("200.00"), 50, true));
    }

    @Test
    void theOrderCreatedPayloadMatchesTheSharedContractExactly() throws Exception {
        Instant before = Instant.now();
        OrderResponse created = orderService.create(
                new CreateOrderRequest(List.of(
                        new OrderItemRequest(FIRST_PRODUCT, 1),
                        new OrderItemRequest(SECOND_PRODUCT, 4)), null),
                customer());

        KafkaOutboxEvent row = kafkaOutboxRepository.findAll().get(0);
        JsonNode payload = objectMapper.readTree(row.getPayload());

        List<String> fields = new ArrayList<>();
        payload.fieldNames().forEachRemaining(fields::add);
        assertThat(fields).containsExactly(
                "eventId", "eventType", "schemaVersion", "orderId",
                "status", "previousStatus", "items", "occurredAt");

        assertThat(UUID.fromString(payload.get("eventId").asText()).toString()).isEqualTo(row.getId());
        assertThat(payload.get("eventType").asText()).isEqualTo("ORDER_CREATED");
        assertThat(payload.get("schemaVersion").isInt()).isTrue();
        assertThat(payload.get("schemaVersion").asInt()).isEqualTo(1);
        assertThat(payload.get("orderId").asText()).isEqualTo(created.id());
        assertThat(payload.get("status").asText()).isEqualTo("CREADO");
        assertThat(payload.has("previousStatus")).isTrue();
        assertThat(payload.get("previousStatus").isNull()).isTrue();

        JsonNode items = payload.get("items");
        assertThat(items.isArray()).isTrue();
        assertThat(items).hasSize(2);
        for (JsonNode item : items) {
            List<String> itemFields = new ArrayList<>();
            item.fieldNames().forEachRemaining(itemFields::add);
            assertThat(itemFields).containsExactly("productId", "quantity");
            assertThat(item.get("quantity").isInt()).isTrue();
        }
        assertThat(items).extracting(item -> item.get("productId").asText() + "x" + item.get("quantity").asInt())
                .containsExactlyInAnyOrder(FIRST_PRODUCT + "x1", SECOND_PRODUCT + "x4");

        String occurredAt = payload.get("occurredAt").asText();
        assertThat(occurredAt).endsWith("Z");
        assertThat(Instant.parse(occurredAt)).isBetween(before, Instant.now());
    }

    @Test
    void theRecordKeyColumnIsTheOrderId() {
        OrderResponse created = orderService.create(
                new CreateOrderRequest(List.of(new OrderItemRequest(FIRST_PRODUCT, 1)), null),
                customer());

        assertThat(kafkaOutboxRepository.findAll())
                .singleElement()
                .satisfies(row -> assertThat(row.getAggregateId()).isEqualTo(created.id()));
    }

    private Authentication customer() {
        Jwt jwt = Jwt.withTokenValue("customer-token")
                .header("alg", "none")
                .claim("oid", "customer-id")
                .claim("roles", List.of("Cliente"))
                .build();
        return new JwtAuthenticationToken(jwt, List.of(new SimpleGrantedAuthority("ROLE_Cliente")));
    }
}
