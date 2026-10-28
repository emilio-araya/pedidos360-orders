package com.pedidos360.orders.order.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pedidos360.orders.catalog.CatalogClient;
import com.pedidos360.orders.order.repository.OrderRepository;
import com.pedidos360.orders.outbox.OutboxEventRepository;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * Guest checkout: buy without logging in and manage the order through a secret
 * tracking link. Requests carry no Authorization header on purpose.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("local")
@ExtendWith(OutputCaptureExtension.class)
class GuestOrderApiIntegrationTest {

    private static final UUID MOUSE_ID = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    private static final String NOT_FOUND_DETAIL = "No existe un pedido para el enlace de seguimiento";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private OrderRepository orderRepository;

    @Autowired
    private OutboxEventRepository outboxRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

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
    }

    @Test
    void createsAGuestOrderWithoutLoginAndStoresOnlyTheTokenHash(CapturedOutput output) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/guest-orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of(
                                "email", "  Luis.Perez@DuocUC.cl ",
                                "items", List.of(Map.of("productId", MOUSE_ID, "quantity", 2)),
                                "notes", "Dejar en conserjería"))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.orderNumber").value(org.hamcrest.Matchers.matchesPattern("^PED-[0-9A-F]{8}$")))
                .andExpect(jsonPath("$.status").value("CREADO"))
                .andExpect(jsonPath("$.items[0].productId").value(MOUSE_ID.toString()))
                .andExpect(jsonPath("$.items[0].productName").value("Mouse inalámbrico"))
                .andExpect(jsonPath("$.items[0].quantity").value(2))
                .andExpect(jsonPath("$.items[0].unitPrice").value(19990.00))
                .andExpect(jsonPath("$.items[0].subtotal").value(39980.00))
                .andExpect(jsonPath("$.total").value(39980.00))
                .andExpect(jsonPath("$.notes").value("Dejar en conserjería"))
                .andExpect(jsonPath("$.maskedEmail").value("l***@duocuc.cl"))
                .andExpect(jsonPath("$.createdAt").exists())
                .andExpect(jsonPath("$.updatedAt").exists())
                .andExpect(jsonPath("$.trackingToken").value(org.hamcrest.Matchers.matchesPattern("^[0-9a-f]{64}$")))
                .andExpect(jsonPath("$.id").doesNotExist())
                .andExpect(jsonPath("$.customerId").doesNotExist())
                .andExpect(jsonPath("$.customerEmail").doesNotExist())
                .andExpect(jsonPath("$.stockReserved").doesNotExist())
                .andReturn();
        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
        String token = body.get("trackingToken").asText();
        String orderNumber = body.get("orderNumber").asText();

        Map<String, Object> row = jdbcTemplate.queryForMap(
                "select id, customer_id, customer_email, guest_token_hash from orders where order_number = ?",
                orderNumber);
        assertThat(row.get("customer_email")).isEqualTo("luis.perez@duocuc.cl");
        assertThat(row.get("guest_token_hash")).isEqualTo(sha256(token));
        assertThat(row.get("customer_id")).isEqualTo("guest:" + row.get("id"));
        assertThat(rawTokenCount(token)).isZero();
        assertThat(result.getResponse().getHeader("Location")).isNull();
        assertThat(output.getAll()).doesNotContain(token);
    }

    @Test
    void rejectsAnInvalidGuestEmailWithoutCreatingAnything() throws Exception {
        for (String email : List.of("", "no-es-un-correo", "a".repeat(250) + "@x.cl")) {
            mockMvc.perform(post("/api/guest-orders")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json(Map.of(
                                    "email", email,
                                    "items", List.of(Map.of("productId", MOUSE_ID, "quantity", 1))))))
                    .andExpect(status().isBadRequest())
                    .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                    .andExpect(jsonPath("$.errors[0].field").value("email"));
        }
        mockMvc.perform(post("/api/guest-orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"luis@duocuc.cl\",\"items\":[]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("items"));

        assertThat(orderRepository.count()).isZero();
    }

    @Test
    void showsTheOrderByTokenWithoutInternalFieldsOrTheFullEmail(CapturedOutput output) throws Exception {
        String token = createGuestOrder("luis@duocuc.cl");

        MvcResult result = mockMvc.perform(get("/api/guest-orders/{token}", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.orderNumber").exists())
                .andExpect(jsonPath("$.status").value("CREADO"))
                .andExpect(jsonPath("$.items[0].productName").value("Mouse inalámbrico"))
                .andExpect(jsonPath("$.total").value(19990.00))
                .andExpect(jsonPath("$.maskedEmail").value("l***@duocuc.cl"))
                .andExpect(jsonPath("$.trackingToken").doesNotExist())
                .andExpect(jsonPath("$.id").doesNotExist())
                .andExpect(jsonPath("$.customerId").doesNotExist())
                .andExpect(jsonPath("$.customerEmail").doesNotExist())
                .andExpect(jsonPath("$.stockReserved").doesNotExist())
                .andReturn();
        assertThat(result.getResponse().getContentAsString()).doesNotContain("luis@duocuc.cl");
        assertThat(output.getAll()).doesNotContain(token);
    }

    @Test
    void answersTheSameNotFoundForMalformedAndUnknownTokens() throws Exception {
        createGuestOrder("luis@duocuc.cl");
        String unknown = "0".repeat(64);
        String uppercase = "A".repeat(64);

        for (String token : List.of("abc", uppercase, unknown)) {
            mockMvc.perform(get("/api/guest-orders/{token}", token))
                    .andExpect(status().isNotFound())
                    .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                    .andExpect(jsonPath("$.detail").value(NOT_FOUND_DETAIL));
            mockMvc.perform(post("/api/guest-orders/{token}/cancel", token))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.detail").value(NOT_FOUND_DETAIL));
        }
        assertThat(outboxRepository.count()).isZero();
    }

    @Test
    void cancelsACreatedGuestOrderAndRecordsTheCancelledEvent() throws Exception {
        String token = createGuestOrder("luis@duocuc.cl");

        mockMvc.perform(post("/api/guest-orders/{token}/cancel", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELADO"))
                .andExpect(jsonPath("$.maskedEmail").value("l***@duocuc.cl"))
                .andExpect(jsonPath("$.trackingToken").doesNotExist())
                .andExpect(jsonPath("$.id").doesNotExist());

        String orderId = orderIdFor(token);
        assertThat(outboxRepository.findAll()).singleElement().satisfies(event -> {
            assertThat(event.getEventType()).isEqualTo("pedido.cancelado");
            assertThat(event.getAggregateId()).isEqualTo(orderId);
            assertThat(event.getPayload()).doesNotContain(token);
        });
        assertThat(rawTokenCount(token)).isZero();
    }

    @Test
    void refusesToCancelAGuestOrderThatIsNoLongerCreated() throws Exception {
        String token = createGuestOrder("luis@duocuc.cl");
        String orderId = orderIdFor(token);
        mockMvc.perform(patch("/api/orders/{id}/status", orderId)
                        .with(operator())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"ACEPTADO\"}"))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/guest-orders/{token}/cancel", token))
                .andExpect(status().isConflict())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.detail").value("Solo se puede cancelar un pedido en estado CREADO"));

        mockMvc.perform(get("/api/guest-orders/{token}", token))
                .andExpect(jsonPath("$.status").value("ACEPTADO"));
        assertThat(outboxRepository.findAll())
                .extracting(event -> event.getEventType())
                .containsExactly("pedido.aceptado");
    }

    @Test
    void staffSeesGuestOrdersWithTheirEmailAndCustomersDoNot() throws Exception {
        String token = createGuestOrder("luis@duocuc.cl");
        String guestOrderId = orderIdFor(token);
        mockMvc.perform(post("/api/orders")
                        .with(customer("customer-a"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("items", List.of(Map.of("productId", MOUSE_ID, "quantity", 1))))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.customerEmail").doesNotExist());

        mockMvc.perform(get("/api/orders/{id}", guestOrderId).with(operator()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.customerEmail").value("luis@duocuc.cl"));
        mockMvc.perform(get("/api/orders").with(operator()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(2));

        mockMvc.perform(get("/api/orders/{id}", guestOrderId).with(customer("customer-a")))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/orders").with(customer("customer-a")))
                .andExpect(jsonPath("$.totalElements").value(1));
    }

    @Test
    void keepsGuestRoutesOutOfTheCognitoNamespace() throws Exception {
        mockMvc.perform(post("/aws/api/guest-orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of(
                                "email", "luis@duocuc.cl",
                                "items", List.of(Map.of("productId", MOUSE_ID, "quantity", 1))))))
                .andExpect(status().isUnauthorized());
        assertThat(orderRepository.count()).isZero();
    }

    private String createGuestOrder(String email) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/guest-orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of(
                                "email", email,
                                "items", List.of(Map.of("productId", MOUSE_ID, "quantity", 1))))))
                .andExpect(status().isCreated())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).get("trackingToken").asText();
    }

    private String orderIdFor(String token) {
        return jdbcTemplate.queryForObject(
                "select id from orders where guest_token_hash = ?", String.class, sha256(token));
    }

    /** Counts persisted rows that contain the raw token anywhere they could leak. */
    private int rawTokenCount(String token) {
        String pattern = "%" + token + "%";
        Integer orders = jdbcTemplate.queryForObject(
                "select count(*) from orders where customer_id like ? or customer_email like ?"
                        + " or guest_token_hash like ? or notes like ?",
                Integer.class, pattern, pattern, pattern, pattern);
        Integer outbox = jdbcTemplate.queryForObject(
                "select count(*) from outbox_event where payload like ?", Integer.class, pattern);
        return orders + outbox;
    }

    private static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
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
