package com.pedidos360.orders.events;

import static org.assertj.core.api.Assertions.assertThat;

import com.pedidos360.orders.catalog.CatalogClient;
import java.util.Map;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.kafka.core.ProducerFactory;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * El productor exige confirmación de todas las réplicas y es idempotente, y el
 * valor viaja como el JSON ya serializado en la outbox. En la suite el
 * publicador está apagado, así que ningún test abre conexión con Kafka.
 */
@SpringBootTest
@ActiveProfiles("local")
class KafkaProducerConfigurationTest {

    @Autowired
    private ProducerFactory<?, ?> producerFactory;

    @Autowired
    private ApplicationContext context;

    @MockitoBean
    private CatalogClient catalogClient;

    @Test
    void theProducerWaitsForAllReplicasAndIsIdempotent() {
        Map<String, Object> config = producerFactory.getConfigurationProperties();

        assertThat(String.valueOf(config.get(ProducerConfig.ACKS_CONFIG))).isEqualTo("all");
        assertThat(String.valueOf(config.get(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG))).isEqualTo("true");
        assertThat(config.get(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG)).isEqualTo(StringSerializer.class);
        assertThat(config.get(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG)).isEqualTo(StringSerializer.class);
        assertThat(String.valueOf(config.get(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG)))
                .contains("localhost:9092");
    }

    @Test
    void thePublisherIsDisabledInTheTestSuite() {
        assertThat(context.getBeansOfType(KafkaOutboxPublisher.class)).isEmpty();
    }
}
