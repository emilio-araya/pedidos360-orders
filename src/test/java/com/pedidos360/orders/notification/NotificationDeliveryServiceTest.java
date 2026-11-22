package com.pedidos360.orders.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import com.pedidos360.orders.events.NotificationRequested;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class NotificationDeliveryServiceTest {

    @Mock
    private NotificationDeliveryRepository repository;

    private NotificationDeliveryService service;

    private final Instant now = Instant.parse("2026-11-22T12:00:00Z");
    private final UUID eventId = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    private final NotificationRequested notification = new NotificationRequested(
            eventId,
            UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb"),
            "PED-12345678",
            "ACEPTADO",
            "CREADO",
            "guest@example.com",
            "2026-11-22T11:59:00Z");

    @BeforeEach
    void setUp() {
        service = new NotificationDeliveryService(repository);
    }

    @Test
    void aNewEventIsPersistedAsProcessingBeforeTheListenerSendsIt() {
        when(repository.findByEventIdForUpdate(eventId.toString())).thenReturn(Optional.empty());

        assertThat(service.tryPrepare(notification, now)).isTrue();

        verify(repository).findByEventIdForUpdate(eventId.toString());
        verify(repository).saveAndFlush(any(NotificationDelivery.class));
        verifyNoMoreInteractions(repository);
    }

    @Test
    void aFreshProcessingClaimIsNotGrantedToASecondConsumer() {
        NotificationDelivery inFlight = NotificationDelivery.begin(
                eventId.toString(), notification.orderNumber(), notification.email(), now.minusSeconds(5));
        when(repository.findByEventIdForUpdate(eventId.toString())).thenReturn(Optional.of(inFlight));

        assertThat(service.tryPrepare(notification, now)).isFalse();

        verify(repository).findByEventIdForUpdate(eventId.toString());
        verifyNoMoreInteractions(repository);
    }

    @Test
    void aSentEventIsNeverClaimedAgain() {
        NotificationDelivery sent = NotificationDelivery.begin(
                eventId.toString(), notification.orderNumber(), notification.email(), now.minusSeconds(20));
        sent.markSent(now.minusSeconds(10));
        when(repository.findByEventIdForUpdate(eventId.toString())).thenReturn(Optional.of(sent));

        assertThat(service.tryPrepare(notification, now)).isFalse();

        verify(repository).findByEventIdForUpdate(eventId.toString());
        verifyNoMoreInteractions(repository);
    }

    @Test
    void aFailedDeliveryCanBeReclaimedAfterTheRowLockIsAcquired() {
        NotificationDelivery failed = NotificationDelivery.begin(
                eventId.toString(), notification.orderNumber(), notification.email(), now.minusSeconds(20));
        failed.markFailed(now.minusSeconds(10), "SMTP unavailable");
        when(repository.findByEventIdForUpdate(eventId.toString())).thenReturn(Optional.of(failed));

        assertThat(service.tryPrepare(notification, now)).isTrue();
        assertThat(failed.getStatus()).isEqualTo(NotificationDeliveryStatus.PROCESSING);
        assertThat(failed.getAttempts()).isEqualTo(2);

        verify(repository).findByEventIdForUpdate(eventId.toString());
        verify(repository).save(failed);
        verifyNoMoreInteractions(repository);
    }

    @Test
    void aStaleProcessingDeliveryCanBeReclaimed() {
        NotificationDelivery stale = NotificationDelivery.begin(
                eventId.toString(), notification.orderNumber(), notification.email(), now.minusSeconds(601));
        when(repository.findByEventIdForUpdate(eventId.toString())).thenReturn(Optional.of(stale));

        assertThat(service.tryPrepare(notification, now)).isTrue();
        assertThat(stale.getStatus()).isEqualTo(NotificationDeliveryStatus.PROCESSING);
        assertThat(stale.getAttempts()).isEqualTo(2);

        verify(repository).findByEventIdForUpdate(eventId.toString());
        verify(repository).save(stale);
        verifyNoMoreInteractions(repository);
    }
}
