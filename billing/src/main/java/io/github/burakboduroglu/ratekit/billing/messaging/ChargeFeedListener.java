package io.github.burakboduroglu.ratekit.billing.messaging;

import io.github.burakboduroglu.ratekit.billing.service.ChargeIntakeService;
import io.github.burakboduroglu.ratekit.common.ChargeEvent;
import io.github.burakboduroglu.ratekit.common.ChargeFeedWatermark;
import io.github.burakboduroglu.ratekit.common.Topics;
import org.springframework.kafka.annotation.KafkaHandler;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;

/**
 * Kafka entry point of the charge feed (ADR 0019). The record's type header picks the handler. The
 * offset is committed after the handler returns, so a charge is in billing's table before anything
 * behind it on the partition is read, including the next progress marker.
 */
@Component
@KafkaListener(topics = Topics.CHARGES)
class ChargeFeedListener {

    private final ChargeIntakeService intake;

    ChargeFeedListener(ChargeIntakeService intake) {
        this.intake = intake;
    }

    @KafkaHandler
    void onCharge(ChargeEvent charge) {
        intake.store(charge);
    }

    @KafkaHandler
    void onWatermark(ChargeFeedWatermark watermark, @Header(KafkaHeaders.RECEIVED_PARTITION) int partition) {
        intake.recordWatermark(partition, watermark);
    }
}
