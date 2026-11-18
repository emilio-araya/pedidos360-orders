package com.pedidos360.orders.notification;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.listener.CommonErrorHandler;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.FixedBackOff;

/**
 * Notificaciones: no merecen una DLT propia (un correo perdido no bloquea el
 * dominio), pero merecen tolerancia a fallos transitorios de SMTP. Se reintenta
 * dos veces cada 2 s; agotados, el registro se descarta y queda el error en el
 * log (el evento original sigue en la outbox como publicado).
 */
@Configuration
public class NotificationKafkaConfiguration {

    private static final Logger log = LoggerFactory.getLogger(NotificationKafkaConfiguration.class);

    @Bean
    CommonErrorHandler notificationErrorHandler() {
        FixedBackOff backOff = new FixedBackOff(2000L, 2L);
        return new DefaultErrorHandler(
                (ConsumerRecord<?, ?> record, Exception exception) -> log.error(
                        "Notificación descartada tras reintentos (tópico {}, partición {}, offset {}): {}",
                        record.topic(), record.partition(), record.offset(), exception.getMessage()),
                backOff);
    }
}
