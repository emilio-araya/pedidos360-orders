package com.pedidos360.orders.order.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pedidos360.orders.catalog.CatalogClient;
import com.pedidos360.orders.notification.GuestVoucher;
import com.pedidos360.orders.notification.GuestVoucherMailer;
import com.pedidos360.orders.order.repository.OrderRepository;
import com.pedidos360.orders.outbox.OutboxEventRepository;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * The voucher email and the resend flow. The mailer is a double: the test
 * asserts what would be sent, never that SMTP works.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("local")
@ExtendWith(OutputCaptureExtension.class)
class GuestVoucherApiIntegrationTest {

    private static final UUID MOUSE_ID = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private OrderRepository orderRepository;

    @Autowired
    private OutboxEventRepository outboxRepository;

    @MockitoBean
    private CatalogClient catalogClient;

    @MockitoBean
    private GuestVoucherMailer mailer;

    @BeforeEach
    void setUp() {
        outboxRepository.deleteAll();
        orderRepository.deleteAll();
        reset(catalogClient, mailer);
        when(catalogClient.getProduct(MOUSE_ID)).thenReturn(new CatalogClient.CatalogProduct(
                MOUSE_ID,
                "Mouse inalámbrico",
                new BigDecimal("19990.00"),
                25,
                true
        ));
    }

    @Test
    void emailsTheVoucherWithTheTrackingLinkAfterCreatingTheOrder(CapturedOutput output) throws Exception {
        JsonNode created = createGuestOrder("Luis.Perez@DuocUC.cl");
        String token = created.get("trackingToken").asText();

        GuestVoucher voucher = captureVoucher();
        assertThat(voucher.to()).isEqualTo("luis.perez@duocuc.cl");
        assertThat(voucher.orderNumber()).isEqualTo(created.get("orderNumber").asText());
        assertThat(voucher.status()).isEqualTo("CREADO");
        assertThat(voucher.total()).isEqualByComparingTo("39980.00");
        assertThat(voucher.items()).hasSize(1);
        assertThat(voucher.items().get(0).productName()).isEqualTo("Mouse inalámbrico");
        assertThat(voucher.items().get(0).quantity()).isEqualTo(2);
        assertThat(voucher.trackingLink()).isEqualTo("http://localhost:4200/seguimiento/" + token);
        assertThat(output).doesNotContain(token);
        assertThat(output).doesNotContain("luis.perez@duocuc.cl");
    }

    @Test
    void keepsTheOrderCreatedWhenTheVoucherCannotBeSent() throws Exception {
        doThrow(new IllegalStateException("SMTP caído")).when(mailer).send(any());

        createGuestOrder("luis.perez@duocuc.cl");

        assertThat(orderRepository.count()).isEqualTo(1);
    }

    @Test
    void resendingRotatesTheLinkAndInvalidatesThePreviousOne() throws Exception {
        JsonNode created = createGuestOrder("luis.perez@duocuc.cl");
        String orderNumber = created.get("orderNumber").asText();
        String previousToken = created.get("trackingToken").asText();
        reset(mailer);

        mockMvc.perform(post("/api/guest-orders/link-requests")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("email", " LUIS.PEREZ@duocuc.cl ", "orderNumber", orderNumber))))
                .andExpect(status().isAccepted());

        String rotatedToken = captureVoucher().trackingLink()
                .substring("http://localhost:4200/seguimiento/".length());
        assertThat(rotatedToken).matches("^[0-9a-f]{64}$").isNotEqualTo(previousToken);
        mockMvc.perform(get("/api/guest-orders/{token}", previousToken))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/guest-orders/{token}", rotatedToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.orderNumber").value(orderNumber));
    }

    @Test
    void answersAcceptedWithoutSendingAnythingWhenTheOrderDoesNotMatch() throws Exception {
        createGuestOrder("luis.perez@duocuc.cl");
        reset(mailer);

        mockMvc.perform(post("/api/guest-orders/link-requests")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("email", "otra@duocuc.cl", "orderNumber", "PED-00000000"))))
                .andExpect(status().isAccepted());

        verifyNoInteractions(mailer);
    }

    @Test
    void rejectsMalformedLinkRequests() throws Exception {
        mockMvc.perform(post("/api/guest-orders/link-requests")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("email", "no-es-un-correo", "orderNumber", ""))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors").isArray());

        verifyNoInteractions(mailer);
    }

    private JsonNode createGuestOrder(String email) throws Exception {
        String response = mockMvc.perform(post("/api/guest-orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of(
                                "email", email,
                                "items", List.of(Map.of("productId", MOUSE_ID, "quantity", 2))))))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();
        return objectMapper.readTree(response);
    }

    private GuestVoucher captureVoucher() {
        ArgumentCaptor<GuestVoucher> captor = ArgumentCaptor.forClass(GuestVoucher.class);
        verify(mailer).send(captor.capture());
        return captor.getValue();
    }

    private String json(Object value) throws Exception {
        return objectMapper.writeValueAsString(value);
    }
}
