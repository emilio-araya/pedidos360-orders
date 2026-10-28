package com.pedidos360.orders.outbox;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

/**
 * El publicador sólo puede dar un evento por publicado si el broker confirma y
 * el mensaje llegó a una cola. Cualquier otra cosa lo deja pendiente para el
 * próximo ciclo.
 */
class OutboxPublisherTest {

    private static final String PAYLOAD = "{\"orderId\":\"11111111-1111-1111-1111-111111111111\"}";

    private OutboxEventRepository repository;
    private RabbitTemplate rabbitTemplate;
    private OutboxPublisher publisher;

    @BeforeEach
    void setUp() {
        repository = mock(OutboxEventRepository.class);
        rabbitTemplate = mock(RabbitTemplate.class);
        publisher = new OutboxPublisher(repository, rabbitTemplate, 2_000);
    }

    @Test
    void marksTheEventPublishedWhenTheBrokerConfirms() {
        OutboxEvent event = pendingEvent();
        when(repository.findTop50ByPublishedAtIsNullOrderByCreatedAtAsc()).thenReturn(List.of(event));
        confirmWith(true);

        publisher.publishPending();

        assertThat(event.getPublishedAt()).isNotNull();
        assertThat(event.getAttempts()).isZero();
        verify(repository).save(event);
    }

    @Test
    void sendsTheStoredJsonAsAPersistentJsonMessageIdentifiedByTheEvent() {
        OutboxEvent event = pendingEvent();
        when(repository.findTop50ByPublishedAtIsNullOrderByCreatedAtAsc()).thenReturn(List.of(event));
        confirmWith(true);

        publisher.publishPending();

        ArgumentCaptor<Message> sent = ArgumentCaptor.forClass(Message.class);
        verify(rabbitTemplate).send(
                eq(RabbitConfiguration.EVENTS_EXCHANGE),
                eq("pedido.aceptado"),
                sent.capture(),
                any(CorrelationData.class));
        MessageProperties properties = sent.getValue().getMessageProperties();
        assertThat(new String(sent.getValue().getBody(), UTF_8)).isEqualTo(PAYLOAD);
        assertThat(properties.getContentType()).isEqualTo(MessageProperties.CONTENT_TYPE_JSON);
        assertThat(properties.getContentEncoding()).isEqualTo("UTF-8");
        assertThat(properties.getDeliveryMode()).isEqualTo(MessageDeliveryMode.PERSISTENT);
        assertThat(properties.getMessageId()).isEqualTo(event.getId());
    }

    @Test
    void keepsTheEventPendingWhenTheBrokerNacks() {
        OutboxEvent event = pendingEvent();
        when(repository.findTop50ByPublishedAtIsNullOrderByCreatedAtAsc()).thenReturn(List.of(event));
        confirmWith(false);

        publisher.publishPending();

        assertThat(event.getPublishedAt()).isNull();
        assertThat(event.getAttempts()).isEqualTo(1);
    }

    @Test
    void keepsTheEventPendingWhenTheBrokerIsUnreachable() {
        OutboxEvent event = pendingEvent();
        when(repository.findTop50ByPublishedAtIsNullOrderByCreatedAtAsc()).thenReturn(List.of(event));
        doThrow(new AmqpException("broker down"))
                .when(rabbitTemplate)
                .send(any(String.class), any(String.class), any(Message.class), any(CorrelationData.class));

        publisher.publishPending();

        assertThat(event.getPublishedAt()).isNull();
        assertThat(event.getAttempts()).isEqualTo(1);
        verify(repository).save(event);
    }

    @Test
    void doesNotPublishAnythingWhenThereAreNoPendingEvents() {
        when(repository.findTop50ByPublishedAtIsNullOrderByCreatedAtAsc()).thenReturn(List.of());

        publisher.publishPending();

        verify(rabbitTemplate, never())
                .send(any(String.class), any(String.class), any(Message.class), any(CorrelationData.class));
        verify(repository, never()).save(any());
    }

    private void confirmWith(boolean ack) {
        doAnswer(invocation -> {
            CorrelationData correlation = invocation.getArgument(3);
            correlation.getFuture().complete(new CorrelationData.Confirm(ack, ack ? null : "nack"));
            return null;
        }).when(rabbitTemplate)
                .send(
                        eq(RabbitConfiguration.EVENTS_EXCHANGE),
                        eq("pedido.aceptado"),
                        any(Message.class),
                        any(CorrelationData.class));
    }

    private OutboxEvent pendingEvent() {
        return new OutboxEvent(
                UUID.fromString("11111111-1111-1111-1111-111111111111"),
                "pedido.aceptado",
                PAYLOAD,
                Instant.now());
    }
}
