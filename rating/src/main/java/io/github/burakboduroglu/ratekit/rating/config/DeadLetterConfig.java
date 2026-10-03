package io.github.burakboduroglu.ratekit.rating.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.burakboduroglu.ratekit.common.Topics;
import io.github.burakboduroglu.ratekit.rating.messaging.DeadLetterProducer;
import java.util.Map;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.TopicPartition;
import org.springframework.boot.autoconfigure.kafka.KafkaConnectionDetails;
import org.springframework.boot.autoconfigure.kafka.KafkaProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;

@Configuration
public class DeadLetterConfig {

    /**
     * The broker address comes from {@link KafkaConnectionDetails}, not from the properties alone:
     * that is where Spring Boot resolves it when it is supplied by the environment (container
     * service connections, platform bindings) instead of {@code spring.kafka.bootstrap-servers}.
     */
    @Bean
    DeadLetterProducer deadLetterProducer(KafkaProperties kafkaProperties, KafkaConnectionDetails connection,
                                          ObjectMapper objectMapper) {
        Map<String, Object> properties = kafkaProperties.buildProducerProperties(null);
        properties.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, connection.getProducerBootstrapServers());
        return new DeadLetterProducer(properties, objectMapper);
    }

    /**
     * Sends a failed record to the dead-letter topic, with headers naming the original topic,
     * partition, offset and the exception. Partition -1 lets the producer pick by key, so one
     * account's dead letters stay together.
     */
    @Bean
    DeadLetterPublishingRecoverer deadLetterRecoverer(DeadLetterProducer producer) {
        return new DeadLetterPublishingRecoverer(producer.template(),
                (record, exception) -> new TopicPartition(Topics.USAGE_EVENTS_DLQ, -1));
    }
}
