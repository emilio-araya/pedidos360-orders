package com.pedidos360.orders.messaging;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rabbitmq.client.Channel;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.dao.DataAccessResourceFailureException;

/**
 * Ack manual: se confirma sólo después de procesar. Un fallo transitorio se
 * reprograma en la cola de retardo siguiente; un mensaje veneno o uno que agotó
 * los reintentos se rechaza sin reencolar y el broker lo mueve a la DLQ.
 */
class ResilientConsumerTest {

    private static final String QUEUE = "orders.stock-result";
    private static final long TAG = 42L;
    private static final UUID ORDER_ID = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");

    private RabbitTemplate rabbitTemplate;
    private Channel channel;
    private ResilientConsumer consumer;
    private List<StockResultMessage> handled;

    @BeforeEach
    void setUp() {
        rabbitTemplate = mock(RabbitTemplate.class);
        channel = mock(Channel.class);
        consumer = new ResilientConsumer(rabbitTemplate, new ObjectMapper(), 200);
        handled = new ArrayList<>();
    }

    @Test
    void acknowledgesAfterTheHandlerSucceeds() throws IOException {
        consumer.consume(json(validBody(), null), channel, QUEUE, StockResultMessage.class, handled::add);

        assertThat(handled).containsExactly(
                new StockResultMessage(ORDER_ID, StockResultMessage.RESERVED, null, null));
        verify(channel).basicAck(TAG, false);
        verify(channel, never()).basicNack(anyLong(), anyBoolean(), anyBoolean());
    }

    @Test
    void sendsAPoisonMessageStraightToTheDeadLetterQueue() throws IOException {
        consumer.consume(json(validBody(), null), channel, QUEUE, StockResultMessage.class, failingWith(
                new PoisonMessageException("pedido inexistente")));

        verify(channel).basicNack(TAG, false, false);
        verify(channel, never()).basicAck(anyLong(), anyBoolean());
        verifyNothingRepublished();
    }

    @Test
    void treatsABodyWithoutJsonContentTypeAsPoison() throws IOException {
        Message plain = MessageBuilder.withBody(validBody().getBytes(UTF_8))
                .setContentType(MessageProperties.CONTENT_TYPE_TEXT_PLAIN)
                .setDeliveryTag(TAG)
                .build();

        consumer.consume(plain, channel, QUEUE, StockResultMessage.class, handled::add);

        assertThat(handled).isEmpty();
        verify(channel).basicNack(TAG, false, false);
        verifyNothingRepublished();
    }

    @Test
    void treatsUnreadableJsonAsPoison() throws IOException {
        consumer.consume(json("{no es json", null), channel, QUEUE, StockResultMessage.class, handled::add);

        assertThat(handled).isEmpty();
        verify(channel).basicNack(TAG, false, false);
        verifyNothingRepublished();
    }

    @Test
    void schedulesTheFirstRetryInTheFiveSecondQueue() throws IOException {
        confirmRepublish(true);

        consumer.consume(json(validBody(), null), channel, QUEUE, StockResultMessage.class, transientFailure());

        Message retried = republishedTo(QUEUE + ".retry.1");
        assertThat(retried.getMessageProperties().<Object>getHeader(ResilientConsumer.RETRY_COUNT_HEADER))
                .isEqualTo(1);
        assertThat(new String(retried.getBody(), UTF_8)).isEqualTo(validBody());
        verify(channel).basicAck(TAG, false);
    }

    @Test
    void schedulesEachFollowingRetryInTheNextDelayQueue() throws IOException {
        confirmRepublish(true);

        consumer.consume(json(validBody(), 1), channel, QUEUE, StockResultMessage.class, transientFailure());

        Message retried = republishedTo(QUEUE + ".retry.2");
        assertThat(retried.getMessageProperties().<Object>getHeader(ResilientConsumer.RETRY_COUNT_HEADER))
                .isEqualTo(2);
        verify(channel).basicAck(TAG, false);
    }

    @Test
    void deadLettersTheMessageOnceTheRetriesAreExhausted() throws IOException {
        consumer.consume(json(validBody(), 3), channel, QUEUE, StockResultMessage.class, transientFailure());

        verify(channel).basicNack(TAG, false, false);
        verify(channel, never()).basicAck(anyLong(), anyBoolean());
        verifyNothingRepublished();
    }

    @Test
    void returnsTheMessageToItsQueueWhenTheRetryCannotBeScheduled() throws IOException {
        doThrow(new AmqpException("broker caído"))
                .when(rabbitTemplate).send(any(String.class), any(String.class), any(Message.class), any(CorrelationData.class));

        consumer.consume(json(validBody(), null), channel, QUEUE, StockResultMessage.class, transientFailure());

        verify(channel).basicNack(TAG, false, true);
        verify(channel, never()).basicAck(anyLong(), anyBoolean());
    }

    @Test
    void returnsTheMessageToItsQueueWhenTheRetryIsNotConfirmed() throws IOException {
        confirmRepublish(false);

        consumer.consume(json(validBody(), null), channel, QUEUE, StockResultMessage.class, transientFailure());

        verify(channel).basicNack(TAG, false, true);
        verify(channel, never()).basicAck(anyLong(), anyBoolean());
    }

    private String validBody() {
        return "{\"orderId\":\"" + ORDER_ID + "\",\"outcome\":\"RESERVED\"}";
    }

    private Message json(String body, Integer retryCount) {
        MessageProperties properties = new MessageProperties();
        properties.setContentType(MessageProperties.CONTENT_TYPE_JSON);
        properties.setContentEncoding(UTF_8.name());
        properties.setDeliveryTag(TAG);
        properties.setMessageId("event-1");
        if (retryCount != null) {
            properties.setHeader(ResilientConsumer.RETRY_COUNT_HEADER, retryCount);
        }
        return new Message(body.getBytes(UTF_8), properties);
    }

    private Consumer<StockResultMessage> transientFailure() {
        return failingWith(new DataAccessResourceFailureException("base caída"));
    }

    private Consumer<StockResultMessage> failingWith(RuntimeException failure) {
        return message -> {
            throw failure;
        };
    }

    private void confirmRepublish(boolean ack) {
        doAnswer(invocation -> {
            CorrelationData correlation = invocation.getArgument(3);
            correlation.getFuture().complete(new CorrelationData.Confirm(ack, ack ? null : "nack"));
            return null;
        }).when(rabbitTemplate).send(
                eq(RetryTopology.RETRY_EXCHANGE), any(String.class), any(Message.class), any(CorrelationData.class));
    }

    private Message republishedTo(String retryQueue) {
        ArgumentCaptor<Message> message = ArgumentCaptor.forClass(Message.class);
        verify(rabbitTemplate).send(
                eq(RetryTopology.RETRY_EXCHANGE), eq(retryQueue), message.capture(), any(CorrelationData.class));
        return message.getValue();
    }

    private void verifyNothingRepublished() {
        verify(rabbitTemplate, never())
                .send(any(String.class), any(String.class), any(Message.class), any(CorrelationData.class));
    }
}
