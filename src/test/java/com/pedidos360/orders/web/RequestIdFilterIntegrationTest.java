package com.pedidos360.orders.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("local")
class RequestIdFilterIntegrationTest {

    private static final String HEADER = "X-Request-Id";

    @Autowired
    private MockMvc mockMvc;

    @Test
    void generatesARequestIdWhenTheClientDoesNotSendOne() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/orders").with(operator()))
                .andExpect(status().isOk())
                .andReturn();

        assertThat(result.getResponse().getHeader(HEADER))
                .isNotBlank()
                .matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
    }

    @Test
    void preservesAValidIncomingRequestId() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/orders")
                        .header(HEADER, "checkout-abc.123")
                        .with(operator()))
                .andExpect(status().isOk())
                .andReturn();

        assertThat(result.getResponse().getHeader(HEADER)).isEqualTo("checkout-abc.123");
    }

    @Test
    void replacesAnUnsafeRequestIdInsteadOfLoggingIt() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/orders")
                        .header(HEADER, "abc\ndef")
                        .with(operator()))
                .andExpect(status().isOk())
                .andReturn();

        assertThat(result.getResponse().getHeader(HEADER))
                .isNotEqualTo("abc\ndef")
                .matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
    }

    private static org.springframework.test.web.servlet.request.RequestPostProcessor operator() {
        return jwt().authorities(new SimpleGrantedAuthority("ROLE_Operador"));
    }
}
