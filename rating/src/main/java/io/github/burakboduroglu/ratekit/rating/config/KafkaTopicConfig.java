package io.github.burakboduroglu.ratekit.rating.config;

import io.github.burakboduroglu.ratekit.common.Topics;
import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

@Configuration
public class KafkaTopicConfig {

    /** Created on startup if missing. Replicas is 1 because local and CI run a single broker. */
    @Bean
    NewTopic usageEventsDeadLetterTopic() {
        return TopicBuilder.name(Topics.USAGE_EVENTS_DLQ).partitions(3).replicas(1).build();
    }
}
