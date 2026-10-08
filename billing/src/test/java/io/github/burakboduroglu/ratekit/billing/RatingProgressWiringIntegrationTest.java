package io.github.burakboduroglu.ratekit.billing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.burakboduroglu.ratekit.billing.exception.ChargesInFlightException;
import io.github.burakboduroglu.ratekit.billing.messaging.KafkaRatingProgress;
import io.github.burakboduroglu.ratekit.billing.service.ChargeFeedProgress;
import io.github.burakboduroglu.ratekit.billing.service.InvoiceRunService;
import io.github.burakboduroglu.ratekit.billing.service.RatingProgress;
import io.github.burakboduroglu.ratekit.billing.service.WatermarkChargeFeedProgress;
import io.github.burakboduroglu.ratekit.common.BillingPeriod;
import java.time.YearMonth;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * billing as it runs in production: both checks switched on and wired to the broker and the database
 * from the environment. The other billing tests replace the checks, which once hid a bean that could
 * not be created.
 */
@SpringBootTest(properties = "ratekit.billing.rating-progress.charge-feed-wait=PT0S")
@Testcontainers
class RatingProgressWiringIntegrationTest {

    @Container
    @ServiceConnection
    static final KafkaContainer KAFKA = new KafkaContainer(DockerImageName.parse("apache/kafka:4.3.1"));

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(DockerImageName.parse("postgres:17.11-alpine"));

    @Autowired
    RatingProgress progress;

    @Autowired
    ChargeFeedProgress chargeFeed;

    @Autowired
    InvoiceRunService runs;

    @Test
    void bothChecksAreWiredAndARatingNeverHeardFromIsNotTakenAsDelivered() {
        assertThat(progress).isInstanceOf(KafkaRatingProgress.class);
        assertThat(chargeFeed).isInstanceOf(WatermarkChargeFeedProgress.class);

        // no ingest ever ran, so the usage topic does not exist and rating counts as caught up; but no
        // progress marker from rating's relay has arrived, so billing cannot know it has every charge
        assertThatThrownBy(() -> runs.run(BillingPeriod.of(YearMonth.of(2026, 8))))
                .isInstanceOf(ChargesInFlightException.class);
    }
}
