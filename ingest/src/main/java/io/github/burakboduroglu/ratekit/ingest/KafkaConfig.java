package io.github.burakboduroglu.ratekit.ingest;

import io.github.burakboduroglu.ratekit.common.Topics;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.burakboduroglu.ratekit.common.UsageEvent;
import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.boot.autoconfigure.kafka.DefaultKafkaProducerFactoryCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.support.serializer.JsonSerializer;

@Configuration
class KafkaConfig {

    /** Created on startup if missing. Replicas is 1 because local and CI run a single broker. */
    @Bean
    NewTopic usageEventsTopic() {
        return TopicBuilder.name(Topics.USAGE_EVENTS).partitions(3).replicas(1).build();
    }

    /**
     * Serialize with Spring Boot's ObjectMapper so timestamps are ISO-8601 strings. Kafka's own
     * default writes Instants as epoch numbers, which is a poor contract for other consumers.
     */
    @Bean
    DefaultKafkaProducerFactoryCustomizer isoDatesInKafkaJson(ObjectMapper objectMapper) {
        return factory -> {
            JsonSerializer<UsageEvent> serializer = new JsonSerializer<>(objectMapper);
            serializer.setAddTypeInfo(false);
            @SuppressWarnings("unchecked")
            DefaultKafkaProducerFactory<String, UsageEvent> typed =
                    (DefaultKafkaProducerFactory<String, UsageEvent>) factory;
            typed.setValueSerializer(serializer);
        };
    }
}
