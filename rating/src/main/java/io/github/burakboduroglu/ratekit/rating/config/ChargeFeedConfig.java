package io.github.burakboduroglu.ratekit.rating.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.burakboduroglu.ratekit.rating.messaging.ChargeFeedProducer;
import java.util.Map;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.springframework.boot.autoconfigure.kafka.KafkaConnectionDetails;
import org.springframework.boot.autoconfigure.kafka.KafkaProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(ChargeFeedProperties.class)
public class ChargeFeedConfig {

    /**
     * Every write is acknowledged by all in-sync replicas, and the idempotent producer keeps one
     * partition's records in the order they were sent even when it retries, which is what keeps one
     * account's charges in order. The broker address comes from {@link KafkaConnectionDetails}, as for
     * the dead-letter producer.
     */
    @Bean
    ChargeFeedProducer chargeFeedProducer(KafkaProperties kafkaProperties, KafkaConnectionDetails connection,
                                          ObjectMapper objectMapper, ChargeFeedProperties feed) {
        Map<String, Object> properties = kafkaProperties.buildProducerProperties(null);
        properties.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, connection.getProducerBootstrapServers());
        properties.put(ProducerConfig.ACKS_CONFIG, "all");
        properties.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true);
        // without a broker, fail the pass instead of blocking the relay's transaction for a minute
        properties.put(ProducerConfig.MAX_BLOCK_MS_CONFIG, (int) feed.sendTimeout().toMillis());
        return new ChargeFeedProducer(properties, objectMapper);
    }
}
