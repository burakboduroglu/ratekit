package io.github.burakboduroglu.ratekit.billing;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.burakboduroglu.ratekit.billing.domain.AdjustmentLine;
import io.github.burakboduroglu.ratekit.billing.domain.Invoice;
import io.github.burakboduroglu.ratekit.billing.domain.InvoiceLine;
import io.github.burakboduroglu.ratekit.billing.service.InvoiceRunService;
import io.github.burakboduroglu.ratekit.billing.service.InvoiceService;
import io.github.burakboduroglu.ratekit.common.BillingPeriod;
import io.github.burakboduroglu.ratekit.common.Money;
import java.time.Clock;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * Late charges become adjustment lines on the account's next invoice (ADR 0020). A database of its
 * own, because which accounts a run invoices depends on every earlier month's unbilled charges. Each
 * test uses its own months of 2025 and leaves nothing late behind, so the order of the tests does not
 * matter. The clock is fixed at 15 June 2027; the readiness checks are switched off (they have their
 * own tests).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "ratekit.billing.rating-progress.enabled=false",
        "spring.kafka.listener.auto-startup=false"})
@Testcontainers
class AdjustmentIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(DockerImageName.parse("postgres:17.11-alpine"));

    @TestConfiguration
    static class FixedClock {
        @Bean
        @Primary
        Clock fixedClock() {
            return Clock.fixed(Instant.parse("2027-06-15T12:00:00Z"), ZoneOffset.UTC);
        }
    }

    @Autowired
    InvoiceRunService runs;

    @Autowired
    InvoiceService invoices;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    TestRestTemplate http;

    @Test
    void aLateChargeGoesOnTheNextInvoiceAsAnAdjustmentNamingItsMonthExactlyOnce() {
        String account = id("late");
        charge(account, "jan-1", 10, "0.5000", "2025-01-10T10:00:00Z");
        runs.run(month(2025, 1));
        Money january = invoices.find(account, month(2025, 1)).orElseThrow().total();

        // a dead letter replayed by hand: rated now, for January, which is already invoiced
        charge(account, "jan-late", 4, "0.2000", "2025-01-20T10:00:00Z");
        assertThat(runs.run(month(2025, 1)).created()).isZero();
        charge(account, "feb-1", 2, "0.1000", "2025-02-03T10:00:00Z");
        runs.run(month(2025, 2));
        runs.run(month(2025, 2)); // a rerun bills nothing twice

        assertThat(invoices.find(account, month(2025, 1)).orElseThrow().total()).isEqualTo(january);
        Invoice february = invoices.find(account, month(2025, 2)).orElseThrow();
        assertThat(february.lines()).containsExactly(new InvoiceLine("sms", 2, Money.of("0.10")));
        assertThat(february.adjustments()).containsExactly(new AdjustmentLine(month(2025, 1), "sms", 4, Money.of("0.20")));
        assertThat(february.total()).isEqualTo(Money.of("0.30"));
        // the next month has nothing left to bill for the account
        runs.run(month(2025, 3));
        assertThat(invoices.find(account, month(2025, 3))).isEmpty();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM charges WHERE account_id = ? AND invoice_id IS NULL",
                Integer.class, account)).isZero();
    }

    @Test
    void anAccountWithOnlyLateUsageGetsAnInvoiceOfAdjustmentsAndTheApiShowsThem() {
        String account = id("only-late");
        charge(account, "apr-1", 1, "0.0500", "2025-04-10T10:00:00Z");
        runs.run(month(2025, 4));
        charge(account, "apr-late", 3, "0.1500", "2025-04-28T10:00:00Z");

        assertThat(runs.run(month(2025, 5)).created()).isGreaterThanOrEqualTo(1);

        Invoice may = invoices.find(account, month(2025, 5)).orElseThrow();
        assertThat(may.lines()).isEmpty();
        assertThat(may.adjustments()).containsExactly(new AdjustmentLine(month(2025, 4), "sms", 3, Money.of("0.15")));
        assertThat(http.getForObject("/v1/invoices/" + account + "?period=2025-05", String.class))
                .contains("\"lines\":[]")
                .contains("\"adjustments\":[{\"originalPeriod\":\"2025-04\",\"meter\":\"sms\",\"quantity\":3,\"amount\":\"0.1500\"}]")
                .contains("\"total\":\"0.1500\"");
    }

    @Test
    void usageOfAMonthNeverInvoicedIsNotLateAndWaitsForItsOwnRun() {
        String account = id("unrun");
        charge(account, "jul-1", 1, "0.0500", "2025-07-10T10:00:00Z");

        runs.run(month(2025, 8));
        assertThat(invoices.find(account, month(2025, 8))).isEmpty();

        runs.run(month(2025, 7));
        Invoice july = invoices.find(account, month(2025, 7)).orElseThrow();
        assertThat(july.lines()).containsExactly(new InvoiceLine("sms", 1, Money.of("0.05")));
        assertThat(july.adjustments()).isEmpty();
    }

    @Test
    void twoRunsRacingForTheSameLateChargeBillItOnce() throws Exception {
        String account = id("race");
        charge(account, "sep-1", 1, "0.0500", "2025-09-10T10:00:00Z");
        runs.run(month(2025, 9));
        charge(account, "sep-late", 7, "0.3500", "2025-09-25T10:00:00Z");
        charge(account, "oct-1", 1, "0.0500", "2025-10-10T10:00:00Z");
        charge(account, "nov-1", 1, "0.0500", "2025-11-10T10:00:00Z");

        // October and November both bill late September usage; only one of them may take it
        CountDownLatch go = new CountDownLatch(1);
        List<CompletableFuture<Void>> racing = List.of(10, 11).stream()
                .map(m -> CompletableFuture.runAsync(() -> {
                    try {
                        go.await();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    runs.run(month(2025, m));
                }))
                .toList();
        go.countDown();
        CompletableFuture.allOf(racing.toArray(CompletableFuture[]::new)).get();

        List<AdjustmentLine> adjustments = List.of(10, 11).stream()
                .flatMap(m -> invoices.find(account, month(2025, m)).orElseThrow().adjustments().stream())
                .toList();
        assertThat(adjustments).containsExactly(new AdjustmentLine(month(2025, 9), "sms", 7, Money.of("0.35")));
    }

    // ---- helpers ----

    private static BillingPeriod month(int year, int month) {
        return BillingPeriod.of(YearMonth.of(year, month));
    }

    private static String id(String prefix) {
        return prefix + "-" + UUID.randomUUID();
    }

    private void charge(String account, String eventId, long quantity, String amount, String occurredAt) {
        jdbc.update("INSERT INTO charges (account_id, event_id, meter, quantity, amount, occurred_at, rated_at) "
                        + "VALUES (?, ?, 'sms', ?, ?::numeric, ?::timestamptz, now())",
                account, eventId, quantity, amount, occurredAt);
    }
}
