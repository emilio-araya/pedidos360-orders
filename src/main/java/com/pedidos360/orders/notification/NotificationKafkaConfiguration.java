package com.pedidos360.orders.notification;

import com.pedidos360.orders.events.KafkaTopics;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.TopicPartition;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.util.backoff.FixedBackOff;

/**
 * Manejo de errores del consumidor de notificaciones. Spring Boot aplica este
 * {@link DefaultErrorHandler} a la fábrica de contenedores por defecto:
 * dos reintentos cada 2 s por errores transitorios de SMTP; agotados, el
 * mensaje va a `pedidos360.notification-events.DLT` junto con sus headers
 * ({@code originalTopic}, {@code originalPartition}, {@code originalOffset},
 * {@code exceptionMessage}). El payload inválido también termina en la DLT en
 * lugar de descartarse en silencio.
 *
 * La fila de `notification_delivery` queda en FAILED junto con el error de
 * cada intento; la DLT sirve para inspección y reproceso manual.
 */
@Configuration(proxyBeanMethods = false)
public class NotificationKafkaConfiguration {

    static final TopicPartition DEAD_LETTER_PARTITION = new TopicPartition(
            KafkaTopics.NOTIFICATION_EVENTS_DLT, -1);

    @Bean
    DefaultErrorHandler notificationErrorHandler(KafkaTemplate<String, String> kafkaTemplate) {
        FixedBackOff backOff = new FixedBackOff(2000L, 2L);
        DeadLetterPublishingRecoverer recoverer = new DeadLetterPublishingRecoverer(
                kafkaTemplate, NotificationKafkaConfiguration::deadLetterDestination);
        return new DefaultErrorHandler(recoverer, backOff);
    }

    /**
     * Partición negativa: la clave del registro (`orderId`) elige la partición,
     * igual que en el resto del dominio.
     */
    static TopicPartition deadLetterDestination(ConsumerRecord<?, ?> record, Exception exception) {
        return DEAD_LETTER_PARTITION;
    }
}
