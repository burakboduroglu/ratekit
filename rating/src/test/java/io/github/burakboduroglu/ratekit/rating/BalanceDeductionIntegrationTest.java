package io.github.burakboduroglu.ratekit.rating;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.burakboduroglu.ratekit.common.UsageEvent;
import io.github.burakboduroglu.ratekit.rating.service.RatingService;
import io.github.burakboduroglu.ratekit.rating.service.RatingService.Outcome;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/** The prepaid hard stop, against a real PostgreSQL, including many events racing for one balance. */
// no Kafka here: the service is called directly, so keep the listener from connecting anywhere
@SpringBootTest(properties = {"spring.kafka.listener.auto-startup=false", "ratekit.rating.charge-feed.enabled=false"})
@Testcontainers
class BalanceDeductionIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(DockerImageName.parse("postgres:17.11-alpine"));

    private static final Instant WHEN = Instant.parse("2026-10-03T10:00:00Z");

    @Autowired
    RatingService rating;

    @Autowired
    JdbcTemplate jdbc;

    @Test
    void anAffordableEventReducesTheBalanceByItsCharge() {
        String account = account("10.0000");
        String meter = flatTariff("0.25");

        assertThat(rating.handle(event(account, "e1", meter, 4))).isEqualTo(Outcome.RATED);

        assertThat(balance(account)).isEqualByComparingTo("9.0000");
        assertThat(count("charges", account)).isEqualTo(1);
    }

    @Test
    void anEventExactlyAsBigAsTheBalanceIsAcceptedAndLeavesZero() {
        String account = account("1.0000");
        String meter = flatTariff("0.25");

        assertThat(rating.handle(event(account, "e1", meter, 4))).isEqualTo(Outcome.RATED);

        assertThat(balance(account)).isEqualByComparingTo("0");
    }

    @Test
    void anUnaffordableEventIsRejectedAndChangesNothingElse() {
        String account = account("0.9999");
        String meter = flatTariff("0.25");

        assertThat(rating.handle(event(account, "e1", meter, 4))).isEqualTo(Outcome.REJECTED);

        assertThat(balance(account)).isEqualByComparingTo("0.9999");
        assertThat(count("charges", account)).isZero();
        assertThat(count("processed_events", account)).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT amount FROM rejected_events WHERE account_id = ? AND event_id = 'e1'",
                BigDecimal.class, account)).isEqualByComparingTo("1.0000"); // what it would have cost
        assertThat(jdbc.queryForObject(
                "SELECT reason FROM rejected_events WHERE account_id = ?", String.class, account))
                .isEqualTo("INSUFFICIENT_BALANCE");
    }

    @Test
    void aFreeEventIsAcceptedEvenWithAnEmptyBalance() {
        String account = account("0");
        String meter = "free-" + UUID.randomUUID();
        tariff(meter, "FREE_QUOTA_THEN_FLAT", "{\"freeUnits\":100,\"rate\":\"1\"}");

        assertThat(rating.handle(event(account, "e1", meter, 50))).isEqualTo(Outcome.RATED);

        assertThat(count("charges", account)).isEqualTo(1);
        assertThat(balance(account)).isEqualByComparingTo("0");
    }

    @Test
    void aRejectedEventIsNotReevaluatedWhenItIsRedeliveredAfterATopUp() {
        String account = account("0");
        String meter = flatTariff("1");
        assertThat(rating.handle(event(account, "e1", meter, 5))).isEqualTo(Outcome.REJECTED);

        jdbc.update("UPDATE accounts SET balance = 100 WHERE id = ?", account);

        assertThat(rating.handle(event(account, "e1", meter, 5))).isEqualTo(Outcome.DUPLICATE);
        assertThat(count("charges", account)).isZero();
        assertThat(balance(account)).isEqualByComparingTo("100");
    }

    @Test
    void manyEventsRacingForOneBalanceNeverOverspendIt() throws Exception {
        int events = 60;
        String account = account("10.0000");
        String meter = flatTariff("1.00"); // each event costs 1.00, so exactly 10 fit

        ExecutorService pool = Executors.newFixedThreadPool(16);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<Outcome>> results = new ArrayList<>();
        for (int i = 0; i < events; i++) {
            UsageEvent e = event(account, "race-" + i, meter, 1);
            Callable<Outcome> task = () -> {
                go.await();
                return rating.handle(e);
            };
            results.add(pool.submit(task));
        }
        go.countDown();
        List<Outcome> outcomes = new ArrayList<>();
        for (Future<Outcome> f : results) {
            outcomes.add(f.get());
        }
        pool.shutdown();

        long rated = outcomes.stream().filter(o -> o == Outcome.RATED).count();
        long rejected = outcomes.stream().filter(o -> o == Outcome.REJECTED).count();
        assertThat(rated).isEqualTo(10);
        assertThat(rejected).isEqualTo(events - 10);
        assertThat(balance(account)).isEqualByComparingTo("0");
        assertThat(count("charges", account)).isEqualTo(10);
        assertThat(count("rejected_events", account)).isEqualTo(events - 10);
        assertThat(count("processed_events", account)).isEqualTo(events);
    }

    // ---- helpers ----

    private static UsageEvent event(String account, String eventId, String meter, long quantity) {
        return new UsageEvent(eventId, account, meter, quantity, WHEN);
    }

    private String account(String balance) {
        String id = "acc-" + UUID.randomUUID();
        jdbc.update("INSERT INTO accounts (id, balance) VALUES (?, ?::numeric)", id, balance);
        return id;
    }

    private String flatTariff(String rate) {
        String meter = "flat-" + UUID.randomUUID();
        tariff(meter, "FLAT", "{\"rate\":\"" + rate + "\"}");
        return meter;
    }

    private void tariff(String meter, String model, String params) {
        jdbc.update("INSERT INTO tariffs (meter, model, effective_from, params) "
                + "VALUES (?, ?, '2026-01-01T00:00:00Z'::timestamptz, ?::jsonb)", meter, model, params);
    }

    private BigDecimal balance(String account) {
        return jdbc.queryForObject("SELECT balance FROM accounts WHERE id = ?", BigDecimal.class, account);
    }

    private int count(String table, String account) {
        return jdbc.queryForObject("SELECT count(*) FROM " + table + " WHERE account_id = ?", Integer.class, account);
    }
}
