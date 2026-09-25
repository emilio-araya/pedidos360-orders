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

    @MockitoBean
    private CatalogClient catalogClient;

    @BeforeEach
    void setUp() {
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
                .andExpect(jsonPath("$.length()").value(0));
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
                .andExpect(jsonPath("$.length()").value(0));

        mockMvc.perform(get("/api/orders/{id}", created.id()).with(operator()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(created.id()));

        mockMvc.perform(get("/api/orders").with(operator()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1));
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
        verify(catalogClient, never()).reserveStock(any(), any());

        mockMvc.perform(statusChange(created.id(), "ACEPTADO").with(operator()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACEPTADO"))
                .andExpect(jsonPath("$.stockReserved").value(true));

        mockMvc.perform(statusChange(created.id(), "DESPACHADO").with(operator()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value("Transición no permitida: ACEPTADO -> DESPACHADO"));

        mockMvc.perform(statusChange(created.id(), "EN_PREPARACION").with(operator()))
                .andExpect(status().isOk());
        mockMvc.perform(statusChange(created.id(), "DESPACHADO").with(operator()))
                .andExpect(status().isOk());
        mockMvc.perform(statusChange(created.id(), "ENTREGADO").with(operator()))
                .andExpect(status().isOk());
        mockMvc.perform(statusChange(created.id(), "CANCELADO").with(operator()))
                .andExpect(status().isConflict());

        verify(catalogClient, times(1)).reserveStock(eq(UUID.fromString(created.id())), any());
    }

    @Test
    void releasesStockOnlyWhenReservedOrderIsCancelled() throws Exception {
        OrderResponse createdWithoutReservation = create(customer("customer-a"), orderWith(MOUSE_ID, 1));

        mockMvc.perform(delete("/api/orders/{id}", createdWithoutReservation.id())
                        .with(customer("customer-a")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELADO"));
        verify(catalogClient, never()).releaseStock(any());

        OrderResponse reserved = create(customer("customer-a"), orderWith(MOUSE_ID, 3));
        mockMvc.perform(statusChange(reserved.id(), "ACEPTADO").with(operator()))
                .andExpect(status().isOk());

        mockMvc.perform(statusChange(reserved.id(), "CANCELADO").with(customer("customer-a")))
                .andExpect(status().isForbidden());
        verify(catalogClient, never()).releaseStock(any());

        mockMvc.perform(statusChange(reserved.id(), "CANCELADO").with(operator()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELADO"))
                .andExpect(jsonPath("$.stockReserved").value(false));
        verify(catalogClient, times(1)).releaseStock(UUID.fromString(reserved.id()));
    }

    @Test
    void failedReservationRollsBackStateAndCanBeRetried() throws Exception {
        OrderResponse created = create(customer("customer-a"), orderWith(MOUSE_ID, 2));
        doThrow(new CatalogServiceException("catalog unavailable"))
                .when(catalogClient).reserveStock(eq(UUID.fromString(created.id())), any());

        mockMvc.perform(statusChange(created.id(), "ACEPTADO").with(operator()))
                .andExpect(status().isBadGateway());

        mockMvc.perform(get("/api/orders/{id}", created.id()).with(customer("customer-a")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CREADO"))
                .andExpect(jsonPath("$.stockReserved").value(false));

        doNothing().when(catalogClient).reserveStock(eq(UUID.fromString(created.id())), any());
        mockMvc.perform(statusChange(created.id(), "ACEPTADO").with(operator()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACEPTADO"));
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
