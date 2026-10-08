package io.github.burakboduroglu.ratekit.rating.messaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.burakboduroglu.ratekit.common.Topics;
import java.util.Map;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.serializer.JsonSerializer;

/**
 * The Kafka producer of the charge feed. Values are JSON with a short type name in a header
 * ({@link Topics#CHARGES_TYPE_MAPPING}), because the topic carries charges and progress markers.
 *
 * <p>Like {@link DeadLetterProducer} it is not exposed as a {@code KafkaTemplate} or
 * {@code ProducerFactory} bean, which would switch off Spring Boot's own Kafka defaults.
 */
public class ChargeFeedProducer implements DisposableBean {

    private final DefaultKafkaProducerFactory<String, Object> factory;
    private final KafkaTemplate<String, Object> template;

    public ChargeFeedProducer(Map<String, Object> producerProperties, ObjectMapper objectMapper) {
        JsonSerializer<Object> json = new JsonSerializer<>(objectMapper);
        json.configure(Map.of(JsonSerializer.TYPE_MAPPINGS, Topics.CHARGES_TYPE_MAPPING), false);
        this.factory = new DefaultKafkaProducerFactory<>(producerProperties, new StringSerializer(), json);
        this.template = new KafkaTemplate<>(factory);
    }

    public KafkaTemplate<String, Object> template() {
        return template;
    }

    @Override
    public void destroy() {
        factory.destroy();
    }
}
