package com.pedidos360.orders.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.pedidos360.orders.events.NotificationRequested;
import java.time.Instant;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * Idempotencia real contra la base del test H2: una reentrega del mismo
 * evento (mismo `event_id`) no duplica el correo ni deja la fila en un estado
 * inconsistente.
 */
@SpringBootTest
@ActiveProfiles("local")
class NotificationDeliveryIntegrationTest {

    private static final String TOPIC = "pedidos360.notification-events";

    @Autowired
    private NotificationListener listener;

    @Autowired
    private NotificationDeliveryRepository repository;

    @MockitoBean
    private OrderStatusMailer mailer;

    private final NotificationRequested notification = new NotificationRequested(
            UUID.randomUUID(), UUID.randomUUID(), "PED-76543210", "DESPACHADO", "EN_PREPARACION",
            "invitado@example.com", "2026-11-19T12:00:00Z");

    private ConsumerRecord<String, String> record() {
        return new ConsumerRecord<>(TOPIC, 0, 7L, "k", payload(notification));
    }

    private static String payload(NotificationRequested n) {
        return """
                {"eventId":"%s","orderId":"%s","orderNumber":"%s","status":"%s","previousStatus":"%s",
                 "email":"%s","occurredAt":"%s"}
                """.formatted(n.eventId(), n.orderId(), n.orderNumber(), n.status(),
                n.previousStatus(), n.email(), n.occurredAt());
    }

    @BeforeEach
    void clean() {
        repository.deleteAll();
        org.mockito.Mockito.reset(mailer);
    }

    @Test
    void aRedeliveryOfTheSameEventDoesNotResendTheMail() {
        listener.onNotification(record());
        listener.onNotification(record());

        verify(mailer, times(1)).send(org.mockito.ArgumentMatchers.any());
        NotificationDelivery row = repository.findById(notification.eventId().toString()).orElseThrow();
        assertThat(row.getStatus()).isEqualTo(NotificationDeliveryStatus.SENT);
        assertThat(row.getAttempts()).isEqualTo(1);
    }

    @Test
    void aTransientSmtpFailureLeavesTheRowFailedAndRetryable() {
        org.mockito.Mockito.doThrow(new RuntimeException("SMTP caído"))
                .when(mailer).send(org.mockito.ArgumentMatchers.any());

        assertThatThrownBy(() -> listener.onNotification(record())).isInstanceOf(RuntimeException.class);
        NotificationDelivery failed = repository.findById(notification.eventId().toString()).orElseThrow();
        assertThat(failed.getStatus()).isEqualTo(NotificationDeliveryStatus.FAILED);
        assertThat(failed.getAttempts()).isEqualTo(1);

        // El SMTP vuelve: la reentrega de Kafka sí sale y queda SENT.
        org.mockito.Mockito.reset(mailer);
        listener.onNotification(record());

        NotificationDelivery sent = repository.findById(notification.eventId().toString()).orElseThrow();
        assertThat(sent.getStatus()).isEqualTo(NotificationDeliveryStatus.SENT);
        assertThat(sent.getAttempts()).isEqualTo(2);
    }

    @Test
    void aSentRowIsNeverResentEvenAcrossRestarts() {
        repository.save(NotificationDelivery.begin(
                notification.eventId().toString(), notification.orderNumber(),
                notification.email(), Instant.now()));
        NotificationDelivery row = repository.findById(notification.eventId().toString()).orElseThrow();
        row.markSent(Instant.now());
        repository.save(row);

        listener.onNotification(record());

        verify(mailer, times(0)).send(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void attemptsAreSyncedWithKafkaRedeliveries() {
        org.mockito.Mockito.doThrow(new RuntimeException("caído"))
                .when(mailer).send(org.mockito.ArgumentMatchers.any());

        for (int i = 0; i < 3; i++) {
            assertThatThrownBy(() -> listener.onNotification(record())).isInstanceOf(RuntimeException.class);
        }

        assertThat(repository.findById(notification.eventId().toString()).orElseThrow().getAttempts())
                .isEqualTo(3);
    }
}
