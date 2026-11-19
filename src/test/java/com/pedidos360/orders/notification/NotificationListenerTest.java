package com.pedidos360.orders.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.pedidos360.orders.events.NotificationRequested;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class NotificationListenerTest {

    private final OrderStatusMailer mailer = mock(OrderStatusMailer.class);
    private final NotificationDeliveryService deliveryService = mock(NotificationDeliveryService.class);
    private final NotificationListener listener =
            new NotificationListener(new ObjectMapper(), mailer, deliveryService);

    private final NotificationRequested valid = new NotificationRequested(
            UUID.randomUUID(), UUID.randomUUID(), "PED-12345678", "ACEPTADO", "CREADO",
            "invitado@example.com", "2026-11-18T20:26:05.336705228Z");

    private ConsumerRecord<String, String> record(String payload) {
        return new ConsumerRecord<>("pedidos360.notification-events", 0, 3L, "k", payload);
    }

    @BeforeEach
    void resetMocks() {
        org.mockito.Mockito.reset(mailer, deliveryService);
    }

    @Test
    void aWellFormedNotificationIsSentOnce() throws Exception {
        String payload = new ObjectMapper().writeValueAsString(valid);
        when(deliveryService.tryPrepare(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
                .thenReturn(true);

        listener.onNotification(record(payload));

        verify(mailer, times(1)).send(valid);
        verify(deliveryService, times(1)).markSent(org.mockito.ArgumentMatchers.eq(valid.eventId().toString()),
                org.mockito.ArgumentMatchers.any());
    }

    @Test
    void anAlreadyProcessedNotificationIsSkippedWithoutMail() throws Exception {
        String payload = new ObjectMapper().writeValueAsString(valid);
        when(deliveryService.tryPrepare(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
                .thenReturn(false);

        listener.onNotification(record(payload));

        verify(mailer, never()).send(org.mockito.ArgumentMatchers.any());
        verify(deliveryService, never()).markSent(org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.any());
    }

    @Test
    void malformedPayloadThrowsSoTheErrorHandlerRoutesToTheDlt() {
        assertThatThrownBy(() -> listener.onNotification(record("{no es json")))
                .isInstanceOf(IllegalArgumentException.class);

        verify(mailer, never()).send(org.mockito.ArgumentMatchers.any());
        verify(deliveryService, never())
                .tryPrepare(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void notificationWithoutEmailThrows() throws Exception {
        NotificationRequested noEmail = new NotificationRequested(
                UUID.randomUUID(), UUID.randomUUID(), "PED-12345678", "ACEPTADO", "CREADO",
                "no-es-un-correo", "2026-11-18T20:26:05.336705228Z");

        assertThatThrownBy(() ->
                        listener.onNotification(record(new ObjectMapper().writeValueAsString(noEmail))))
                .isInstanceOf(IllegalArgumentException.class);

        verify(mailer, never()).send(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void aMailFailureIsRecordedAsFailedBeforeRethrowing() throws Exception {
        String payload = new ObjectMapper().writeValueAsString(valid);
        when(deliveryService.tryPrepare(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
                .thenReturn(true);
        org.mockito.Mockito.doThrow(new RuntimeException("SMTP caído")).when(mailer).send(valid);

        assertThatThrownBy(() -> listener.onNotification(record(payload)))
                .isInstanceOf(RuntimeException.class);

        verify(deliveryService, times(1)).markFailed(org.mockito.ArgumentMatchers.eq(valid.eventId().toString()),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.contains("SMTP"));
    }
}
