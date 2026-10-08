package io.github.burakboduroglu.ratekit.billing.service;

import io.github.burakboduroglu.ratekit.billing.repository.ChargeFeedWatermarkRepository;
import io.github.burakboduroglu.ratekit.billing.repository.ReceivedChargeRepository;
import io.github.burakboduroglu.ratekit.common.ChargeEvent;
import io.github.burakboduroglu.ratekit.common.ChargeFeedWatermark;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Takes in what the charge feed delivers (ADR 0019): charges, stored once however often they arrive,
 * and progress markers, which say how far the stored charges are complete.
 */
@Service
public class ChargeIntakeService {

    private static final Logger log = LoggerFactory.getLogger(ChargeIntakeService.class);

    private final ReceivedChargeRepository charges;
    private final ChargeFeedWatermarkRepository watermarks;
    private final Counter stored;
    private final Counter duplicate;

    public ChargeIntakeService(ReceivedChargeRepository charges, ChargeFeedWatermarkRepository watermarks,
                               MeterRegistry meters) {
        this.charges = charges;
        this.watermarks = watermarks;
        this.stored = received(meters, "stored");
        this.duplicate = received(meters, "duplicate");
    }

    public void store(ChargeEvent charge) {
        if (charges.insertIfAbsent(charge)) {
            stored.increment();
        } else {
            log.info("duplicate charge ignored: account={} event={}", charge.accountId(), charge.eventId());
            duplicate.increment();
        }
    }

    /** Must be called in partition order, after the charges that came before the marker. */
    public void recordWatermark(int partition, ChargeFeedWatermark watermark) {
        watermarks.advance(partition, watermark);
    }

    private static Counter received(MeterRegistry meters, String result) {
        return Counter.builder("ratekit.charges.received")
                .description("Charges delivered to billing, by result")
                .tag("result", result)
                .register(meters);
    }
}
