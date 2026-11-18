package com.pedidos360.orders.notification;

import static org.assertj.core.api.Assertions.assertThat;

import com.pedidos360.orders.events.NotificationRequested;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class OrderStatusMailTemplateTest {

    private final NotificationRequested notification = new NotificationRequested(
            UUID.randomUUID(), UUID.randomUUID(), "PED-12345678", "DESPACHADO", "EN_PREPARACION",
            "invitado@example.com", "2026-11-18T20:26:05.336705228Z");

    @Test
    void subjectCarriesOrderNumberAndNewStatus() {
        assertThat(OrderStatusMailTemplate.subject(notification))
                .contains("PED-12345678")
                .contains("DESPACHADO");
    }

    @Test
    void bodyCarriesOrderNumberTransitionAndTrackingLink() {
        String body = OrderStatusMailTemplate.body(notification, "https://shop.example/");
        assertThat(body)
                .contains("PED-12345678")
                .contains("EN_PREPARACION")
                .contains("DESPACHADO")
                .contains("https://shop.example/seguimiento");
    }

    @Test
    void bodyToleratesABaseUrlWithoutTrailingSlash() {
        String body = OrderStatusMailTemplate.body(notification, "https://shop.example");
        assertThat(body).contains("https://shop.example/seguimiento");
    }
}
