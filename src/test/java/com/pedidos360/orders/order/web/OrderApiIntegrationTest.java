package com.pedidos360.orders.order.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pedidos360.orders.catalog.CatalogClient;
import com.pedidos360.orders.catalog.exception.CatalogServiceException;
import com.pedidos360.orders.order.domain.OrderStatus;
import com.pedidos360.orders.order.dto.OrderResponse;
import com.pedidos360.orders.order.repository.OrderRepository;
import com.pedidos360.orders.order.service.OrderService;
import com.pedidos360.orders.outbox.OutboxEvent;
import com.pedidos360.orders.outbox.OutboxEventRepository;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("local")
class OrderApiIntegrationTest {

    private static final UUID MOUSE_ID = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    private static final UUID KEYBOARD_ID = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private OrderRepository orderRepository;

    @Autowired
    private OutboxEventRepository outboxRepository;

    @Autowired
    private OrderService orderService;

    @MockitoBean
    private CatalogClient catalogClient;

    @BeforeEach
    void setUp() {
        outboxRepository.deleteAll();
        orderRepository.deleteAll();
        reset(catalogClient);
        when(catalogClient.getProduct(MOUSE_ID)).thenReturn(new CatalogClient.CatalogProduct(
                MOUSE_ID,
                "Mouse inalámbrico",
                new BigDecimal("19990.00"),
                25,
                true
        ));
        when(catalogClient.getProduct(KEYBOARD_ID)).thenReturn(new CatalogClient.CatalogProduct(
                KEYBOARD_ID,
                "Teclado",
                new BigDecimal("45990.50"),
                12,
                true
        ));
    }

    @Test
    void rejectsUnauthenticatedRequestsWithProblemDetails() throws Exception {
        mockMvc.perform(get("/api/orders"))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(401));
    }

    @Test
    void cognitoNamespaceExposesTheOrderContract() throws Exception {
        mockMvc.perform(get("/aws/api/orders")
                        .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_Cliente"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(0));
    }

    @Test
    void createsWithCatalogSnapshotAndReplacesItemsWhileCreated() throws Exception {
        OrderResponse created = create(customer("customer-a"), Map.of(
                "items", List.of(Map.of("productId", MOUSE_ID, "quantity", 2)),
                "notes", "Entregar después de las 18:00"
        ));

        assertThat(created.customerId()).isEqualTo("customer-a");
        assertThat(created.status()).isEqualTo(OrderStatus.CREADO);
        assertThat(created.stockReserved()).isFalse();
        assertThat(created.items()).singleElement().satisfies(item -> {
            assertThat(item.productName()).isEqualTo("Mouse inalámbrico");
            assertThat(item.unitPrice()).isEqualByComparingTo("19990.00");
            assertThat(item.subtotal()).isEqualByComparingTo("39980.00");
        });
        assertThat(created.total()).isEqualByComparingTo("39980.00");

        mockMvc.perform(put("/api/orders/{id}", created.id())
                        .with(customer("customer-a"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of(
                                "items", List.of(Map.of("productId", KEYBOARD_ID, "quantity", 1)),
                                "notes", "Teclado"
                        ))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].productName").value("Teclado"))
                .andExpect(jsonPath("$.total").value(45990.50));

        verify(catalogClient).getProduct(MOUSE_ID);
        verify(catalogClient).getProduct(KEYBOARD_ID);
    }

    @Test
    void isolatesOrdersByOidAndLetsStaffSeeAll() throws Exception {
        OrderResponse created = create(customer("customer-a"), orderWith(MOUSE_ID, 1));

        mockMvc.perform(get("/api/orders/{id}", created.id()).with(customer("customer-b")))
                .andExpect(status().isForbidden())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(403));

        mockMvc.perform(get("/api/orders").with(customer("customer-b")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(0))
                .andExpect(jsonPath("$.totalElements").value(0));

        mockMvc.perform(get("/api/orders/{id}", created.id()).with(operator()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(created.id()));

        mockMvc.perform(get("/api/orders").with(operator()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.totalElements").value(1));
    }

    @Test
    void paginatesOrdersAndReportsTheTotal() throws Exception {
        create(customer("customer-a"), orderWith(MOUSE_ID, 1));
        create(customer("customer-a"), orderWith(MOUSE_ID, 2));
        create(customer("customer-a"), orderWith(MOUSE_ID, 3));

        mockMvc.perform(get("/api/orders")
                        .param("page", "0")
                        .param("size", "2")
                        .with(operator()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(2))
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.size").value(2))
                .andExpect(jsonPath("$.totalElements").value(3))
                .andExpect(jsonPath("$.totalPages").value(2))
                .andExpect(jsonPath("$.content[0].items.length()").value(1));

        mockMvc.perform(get("/api/orders")
                        .param("page", "1")
                        .param("size", "2")
                        .with(operator()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.totalElements").value(3));
    }

    @Test
    void rejectsPageRequestsOutsideTheAllowedRange() throws Exception {
        mockMvc.perform(get("/api/orders").param("size", "101").with(operator()))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/orders").param("size", "0").with(operator()))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/orders").param("page", "-1").with(operator()))
                .andExpect(status().isBadRequest());
    }

    @Test
    void appliesDefaultPaginationWhenNoParametersAreGiven() throws Exception {
        create(customer("customer-a"), orderWith(MOUSE_ID, 1));

        mockMvc.perform(get("/api/orders").with(operator()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.size").value(20));
    }

    @Test
    void usesSubWhenOidIsAbsent() throws Exception {
        RequestPostProcessor subjectOnly = jwt()
                .jwt(token -> token
                        .claim("sub", "subject-customer")
                        .claim("roles", List.of("Cliente")))
                .authorities(new SimpleGrantedAuthority("ROLE_Cliente"));

        OrderResponse created = create(subjectOnly, orderWith(MOUSE_ID, 1));
        assertThat(created.customerId()).isEqualTo("subject-customer");
    }

    @Test
    void enforcesOperationalTransitionsAndNeverSkipsPreparation() throws Exception {
        OrderResponse created = create(customer("customer-a"), orderWith(MOUSE_ID, 2));

        mockMvc.perform(statusChange(created.id(), "ACEPTADO").with(customer("customer-a")))
                .andExpect(status().isForbidden());
        assertThat(eventTypes(created.id())).isEmpty();

        mockMvc.perform(statusChange(created.id(), "ACEPTADO").with(operator()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACEPTADO"))
                .andExpect(jsonPath("$.stockReserved").value(false));

        mockMvc.perform(statusChange(created.id(), "DESPACHADO").with(operator()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value("Transición no permitida: ACEPTADO -> DESPACHADO"));

        // Sin la confirmación del catálogo no se prepara: es el invariante que el
        // diseño síncrono garantizaba al aceptar.
        mockMvc.perform(statusChange(created.id(), "EN_PREPARACION").with(operator()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value("El stock del pedido todavía no está confirmado"));

        orderService.applyStockReserved(UUID.fromString(created.id()));
        mockMvc.perform(get("/api/orders/{id}", created.id()).with(customer("customer-a")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.stockReserved").value(true));

        mockMvc.perform(statusChange(created.id(), "EN_PREPARACION").with(operator()))
                .andExpect(status().isOk());
        mockMvc.perform(statusChange(created.id(), "DESPACHADO").with(operator()))
                .andExpect(status().isOk());
        mockMvc.perform(statusChange(created.id(), "ENTREGADO").with(operator()))
                .andExpect(status().isOk());
        mockMvc.perform(statusChange(created.id(), "CANCELADO").with(operator()))
                .andExpect(status().isConflict());

        assertThat(eventTypes(created.id())).containsExactly("pedido.aceptado");
    }

    @Test
    void requestsTheReleaseOnEveryCancellation() throws Exception {
        // Cancelar siempre emite pedido.cancelado, aunque el stock no esté
        // confirmado: el catálogo libera de forma idempotente y así no queda
        // ninguna reserva inmovilizada.
        OrderResponse createdWithoutReservation = create(customer("customer-a"), orderWith(MOUSE_ID, 1));

        mockMvc.perform(delete("/api/orders/{id}", createdWithoutReservation.id())
                        .with(customer("customer-a")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELADO"));
        assertThat(eventTypes(createdWithoutReservation.id())).containsExactly("pedido.cancelado");

        OrderResponse reserved = create(customer("customer-a"), orderWith(MOUSE_ID, 3));
        mockMvc.perform(statusChange(reserved.id(), "ACEPTADO").with(operator()))
                .andExpect(status().isOk());
        orderService.applyStockReserved(UUID.fromString(reserved.id()));

        mockMvc.perform(statusChange(reserved.id(), "CANCELADO").with(customer("customer-a")))
                .andExpect(status().isForbidden());
        assertThat(eventTypes(reserved.id())).containsExactly("pedido.aceptado");

        mockMvc.perform(statusChange(reserved.id(), "CANCELADO").with(operator()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELADO"))
                .andExpect(jsonPath("$.stockReserved").value(false));
        assertThat(eventTypes(reserved.id())).containsExactly("pedido.aceptado", "pedido.cancelado");
    }

    @Test
    void acceptsAndCancelsWithoutDependingOnTheCatalog() throws Exception {
        // Antes, aceptar y cancelar devolvían 502 si el catálogo no respondía.
        // Ahora sólo escriben la outbox en la transacción local.
        OrderResponse toAccept = create(customer("customer-a"), orderWith(MOUSE_ID, 2));
        OrderResponse toCancel = create(customer("customer-a"), orderWith(MOUSE_ID, 1));
        clearInvocations(catalogClient);

        mockMvc.perform(statusChange(toAccept.id(), "ACEPTADO").with(operator()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACEPTADO"));
        mockMvc.perform(delete("/api/orders/{id}", toCancel.id()).with(customer("customer-a")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELADO"));

        verifyNoInteractions(catalogClient);
    }

    @Test
    void exposesTheCancellationReasonWhenTheCatalogRejectsTheReservation() throws Exception {
        OrderResponse created = create(customer("customer-a"), orderWith(MOUSE_ID, 2));
        mockMvc.perform(statusChange(created.id(), "ACEPTADO").with(operator()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.cancellationReason").doesNotExist());

        orderService.applyStockRejected(UUID.fromString(created.id()), "INSUFFICIENT_STOCK", "Stock insuficiente");

        mockMvc.perform(get("/api/orders/{id}", created.id()).with(customer("customer-a")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELADO"))
                .andExpect(jsonPath("$.cancellationReason").value("Stock insuficiente"));
    }

    private List<String> eventTypes(String orderId) {
        return outboxRepository.findAll().stream()
                .filter(event -> event.getAggregateId().equals(orderId))
                .sorted((left, right) -> left.getCreatedAt().compareTo(right.getCreatedAt()))
                .map(OutboxEvent::getEventType)
                .toList();
    }

    @Test
    void validatesPayloadAndReturnsProblemDetails() throws Exception {
        mockMvc.perform(post("/api/orders")
                        .with(customer("customer-a"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"items":[]}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.title").value("Bad Request"))
                .andExpect(jsonPath("$.errors[0].field").value("items"));
    }

    @Test
    void deleteEndpointRejectsAcceptedOrdersEvenForStaff() throws Exception {
        OrderResponse created = create(customer("customer-a"), orderWith(MOUSE_ID, 1));
        mockMvc.perform(statusChange(created.id(), "ACEPTADO").with(operator()))
                .andExpect(status().isOk());

        mockMvc.perform(delete("/api/orders/{id}", created.id()).with(operator()))
                .andExpect(status().isForbidden());
    }

    private OrderResponse create(RequestPostProcessor authentication, Object body) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/orders")
                        .with(authentication)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(body)))
                .andExpect(status().isCreated())
                .andExpect(header().exists("Location"))
                .andReturn();
        OrderResponse created = objectMapper.readValue(
                result.getResponse().getContentAsString(),
                OrderResponse.class
        );
        assertThat(result.getResponse().getHeader("Location"))
                .endsWith("/api/orders/" + created.id());
        return created;
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder statusChange(
            String id,
            String status
    ) {
        return patch("/api/orders/{id}/status", id)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"status\":\"" + status + "\"}");
    }

    private Map<String, Object> orderWith(UUID productId, int quantity) {
        return Map.of("items", List.of(Map.of("productId", productId, "quantity", quantity)));
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    private RequestPostProcessor customer(String oid) {
        return jwt()
                .jwt(token -> token
                        .claim("oid", oid)
                        .claim("sub", "ignored-subject")
                        .claim("roles", List.of("Cliente")))
                .authorities(new SimpleGrantedAuthority("ROLE_Cliente"));
    }

    private RequestPostProcessor operator() {
        return jwt()
                .jwt(token -> token
                        .claim("oid", "operator-id")
                        .claim("roles", List.of("Operador")))
                .authorities(new SimpleGrantedAuthority("ROLE_Operador"));
    }
}
