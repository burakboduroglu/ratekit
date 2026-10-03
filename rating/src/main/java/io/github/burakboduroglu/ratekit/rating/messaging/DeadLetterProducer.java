package io.github.burakboduroglu.ratekit.rating.messaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.burakboduroglu.ratekit.common.UsageEvent;
import java.util.Map;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.Serializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.serializer.DelegatingByTypeSerializer;
import org.springframework.kafka.support.serializer.JsonSerializer;

/**
 * Writes failed records to the dead-letter topic.
 *
 * <p>It needs its own producer because it must send two kinds of value: a {@link UsageEvent} that
 * failed in processing (written as JSON) and the raw bytes of a message that could not even be
 * parsed (written untouched). It is deliberately not exposed as a {@code KafkaTemplate} or
 * {@code ProducerFactory} bean, which would switch off Spring Boot's own Kafka defaults.
 */
public class DeadLetterProducer implements DisposableBean {

    private final DefaultKafkaProducerFactory<Object, Object> factory;
    private final KafkaTemplate<Object, Object> template;

    public DeadLetterProducer(Map<String, Object> producerProperties, ObjectMapper objectMapper) {
        JsonSerializer<UsageEvent> json = new JsonSerializer<>(objectMapper);
        json.setAddTypeInfo(false);
        Map<Class<?>, Serializer<?>> values = Map.of(byte[].class, new ByteArraySerializer(), UsageEvent.class, json);
        Map<Class<?>, Serializer<?>> keys = Map.of(byte[].class, new ByteArraySerializer(), String.class, new StringSerializer());
        this.factory = new DefaultKafkaProducerFactory<>(
                producerProperties, new DelegatingByTypeSerializer(keys), new DelegatingByTypeSerializer(values));
        this.template = new KafkaTemplate<>(factory);
    }

    public KafkaTemplate<Object, Object> template() {
        return template;
    }

    @Override
    public void destroy() {
        factory.destroy();
    }
}
