package com.pedidos360.orders.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.pedidos360.orders.events.NotificationRequested;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class NotificationListenerTest {

    private final OrderStatusMailer mailer = mock(OrderStatusMailer.class);
    private final NotificationListener listener =
            new NotificationListener(new ObjectMapper(), mailer);

    private final NotificationRequested valid = new NotificationRequested(
            UUID.randomUUID(), UUID.randomUUID(), "PED-12345678", "ACEPTADO", "CREADO",
            "invitado@example.com", "2026-11-18T20:26:05.336705228Z");

    private ConsumerRecord<String, String> record(String payload) {
        return new ConsumerRecord<>("pedidos360.notification-events", 0, 3L, "k", payload);
    }

    @BeforeEach
    void resetMailer() {
        org.mockito.Mockito.reset(mailer);
    }

    @Test
    void aWellFormedNotificationIsSent() throws Exception {
        String payload = new ObjectMapper().writeValueAsString(valid);

        listener.onNotification(record(payload));

        verify(mailer, times(1)).send(valid);
    }

    @Test
    void malformedPayloadIsDiscardedWithoutMail() {
        listener.onNotification(record("{no es json"));

        verify(mailer, never()).send(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void notificationWithoutEmailIsDiscardedWithoutMail() throws Exception {
        NotificationRequested noEmail = new NotificationRequested(
                UUID.randomUUID(), UUID.randomUUID(), "PED-12345678", "ACEPTADO", "CREADO",
                "no-es-un-correo", "2026-11-18T20:26:05.336705228Z");

        listener.onNotification(record(new ObjectMapper().writeValueAsString(noEmail)));

        verify(mailer, never()).send(org.mockito.ArgumentMatchers.any());
    }
}
