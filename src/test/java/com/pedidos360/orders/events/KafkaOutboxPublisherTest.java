package com.pedidos360.orders.events;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.kafka.KafkaException;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;

/**
 * El publicador sólo da un evento por publicado cuando Kafka confirma el envío.
 * Ante el primer fallo corta el lote para no adelantar eventos posteriores del
 * mismo pedido.
 */
class KafkaOutboxPublisherTest {

    private static final UUID ORDER_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");

    private KafkaOutboxEventRepository repository;
    private KafkaTemplate<String, String> kafkaTemplate;
    private KafkaOutboxPublisher publisher;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        repository = mock(KafkaOutboxEventRepository.class);
        kafkaTemplate = mock(KafkaTemplate.class);
        publisher = new KafkaOutboxPublisher(repository, kafkaTemplate, 100);
    }

    @Test
    void sendsThePayloadKeyedByOrderIdAndMarksItPublishedAfterTheAck() {
        KafkaOutboxEvent event = pendingEvent("ORDER_CREATED", "{\"n\":1}");
        when(repository.findTop50ByPublishedAtIsNullOrderByCreatedAtAsc()).thenReturn(List.of(event));
        when(kafkaTemplate.send("pedidos360.order-events", ORDER_ID.toString(), "{\"n\":1}"))
                .thenReturn(acked());

        publisher.publishPending();

        assertThat(event.getPublishedAt()).isNotNull();
        assertThat(event.getAttempts()).isZero();
        verify(repository).save(event);
    }

    @Test
    void keepsTheEventPendingWhileTheBrokerHasNotAcknowledged() {
        KafkaOutboxEvent event = pendingEvent("ORDER_CREATED", "{\"n\":1}");
        when(repository.findTop50ByPublishedAtIsNullOrderByCreatedAtAsc()).thenReturn(List.of(event));
        when(kafkaTemplate.send(anyString(), anyString(), anyString())).thenReturn(new CompletableFuture<>());

        publisher.publishPending();

        assertThat(event.getPublishedAt()).isNull();
        assertThat(event.getAttempts()).isEqualTo(1);
        verify(repository).save(event);
    }

    @Test
    void stopsTheBatchOnTheFirstFailedSend() {
        KafkaOutboxEvent first = pendingEvent("ORDER_CREATED", "{\"n\":1}");
        KafkaOutboxEvent second = pendingEvent("ORDER_ACCEPTED", "{\"n\":2}");
        when(repository.findTop50ByPublishedAtIsNullOrderByCreatedAtAsc()).thenReturn(List.of(first, second));
        when(kafkaTemplate.send(anyString(), anyString(), anyString()))
                .thenReturn(CompletableFuture.failedFuture(new KafkaException("broker down")));

        publisher.publishPending();

        assertThat(first.getPublishedAt()).isNull();
        assertThat(first.getAttempts()).isEqualTo(1);
        assertThat(second.getPublishedAt()).isNull();
        assertThat(second.getAttempts()).isZero();
        verify(kafkaTemplate, never()).send(anyString(), anyString(), org.mockito.ArgumentMatchers.eq("{\"n\":2}"));
        verify(repository, never()).save(second);
    }

    @Test
    void stopsTheBatchWhenTheSendThrowsSynchronously() {
        KafkaOutboxEvent first = pendingEvent("ORDER_CREATED", "{\"n\":1}");
        KafkaOutboxEvent second = pendingEvent("ORDER_ACCEPTED", "{\"n\":2}");
        when(repository.findTop50ByPublishedAtIsNullOrderByCreatedAtAsc()).thenReturn(List.of(first, second));
        when(kafkaTemplate.send(anyString(), anyString(), anyString()))
                .thenThrow(new KafkaException("metadata timeout"));

        publisher.publishPending();

        assertThat(first.getAttempts()).isEqualTo(1);
        assertThat(second.getAttempts()).isZero();
        assertThat(second.getPublishedAt()).isNull();
    }

    @Test
    void publishesThePendingEventsInTheOrderTheRepositoryReturnsThem() {
        KafkaOutboxEvent first = pendingEvent("ORDER_CREATED", "{\"n\":1}");
        KafkaOutboxEvent second = pendingEvent("ORDER_ACCEPTED", "{\"n\":2}");
        KafkaOutboxEvent third = pendingEvent("ORDER_CANCELLED", "{\"n\":3}");
        when(repository.findTop50ByPublishedAtIsNullOrderByCreatedAtAsc())
                .thenReturn(List.of(first, second, third));
        when(kafkaTemplate.send(anyString(), anyString(), anyString())).thenReturn(acked());

        publisher.publishPending();

        InOrder order = inOrder(kafkaTemplate, repository);
        order.verify(kafkaTemplate).send("pedidos360.order-events", ORDER_ID.toString(), "{\"n\":1}");
        order.verify(repository).save(first);
        order.verify(kafkaTemplate).send("pedidos360.order-events", ORDER_ID.toString(), "{\"n\":2}");
        order.verify(repository).save(second);
        order.verify(kafkaTemplate).send("pedidos360.order-events", ORDER_ID.toString(), "{\"n\":3}");
        order.verify(repository).save(third);
        assertThat(List.of(first, second, third)).allSatisfy(event -> assertThat(event.getPublishedAt()).isNotNull());
    }

    @Test
    void doesNothingWhenThereAreNoPendingEvents() {
        when(repository.findTop50ByPublishedAtIsNullOrderByCreatedAtAsc()).thenReturn(List.of());

        publisher.publishPending();

        verify(kafkaTemplate, never()).send(anyString(), anyString(), anyString());
        verify(repository, never()).save(any());
    }

    private static CompletableFuture<SendResult<String, String>> acked() {
        return CompletableFuture.completedFuture(new SendResult<>(null, null));
    }

    private static KafkaOutboxEvent pendingEvent(String eventType, String payload) {
        return new KafkaOutboxEvent(UUID.randomUUID(), ORDER_ID, eventType, payload, Instant.now());
    }
}
