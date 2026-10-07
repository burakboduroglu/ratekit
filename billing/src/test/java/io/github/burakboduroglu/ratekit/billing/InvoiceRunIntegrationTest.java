package io.github.burakboduroglu.ratekit.billing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.burakboduroglu.ratekit.common.BillingPeriod;
import io.github.burakboduroglu.ratekit.common.Money;
import io.github.burakboduroglu.ratekit.billing.domain.Invoice;
import io.github.burakboduroglu.ratekit.billing.domain.InvoiceLine;
import io.github.burakboduroglu.ratekit.billing.exception.PeriodNotClosedException;
import io.github.burakboduroglu.ratekit.billing.service.InvoiceRunService;
import io.github.burakboduroglu.ratekit.billing.service.InvoiceRunService.RunSummary;
import io.github.burakboduroglu.ratekit.billing.exception.RatingNotCaughtUpException;
import io.github.burakboduroglu.ratekit.billing.service.InvoiceService;
import io.github.burakboduroglu.ratekit.billing.service.RatingProgress;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * Invoice runs against a real PostgreSQL. The clock is fixed at 15 June 2027, so every 2026 month is
 * closed and June 2027 is still open. Each test uses its own month, so tests cannot see each
 * other's charges.
 */
// @SpringBootTest switches metric export off; turn it on to test the Prometheus endpoint
@AutoConfigureObservability
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.flyway.locations=classpath:db/billing,classpath:db/billing-test",
        "ratekit.billing.batch-size=2",
        // the Kafka check has its own test; here a switch stands in for it
        "ratekit.billing.rating-progress.enabled=false"})
@Testcontainers
class InvoiceRunIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(DockerImageName.parse("postgres:17.11-alpine"));

    /** Whether the stand-in reports rating as caught up; tests that change it set it back. */
    static final AtomicBoolean RATING_CAUGHT_UP = new AtomicBoolean(true);

    @TestConfiguration
    static class FixedClock {
        @Bean
        @Primary
        RatingProgress switchableRatingProgress() {
            return cutoff -> RATING_CAUGHT_UP.get();
        }

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

    @Autowired
    MeterRegistry meters;

    private static BillingPeriod month(int year, int month) {
        return BillingPeriod.of(YearMonth.of(year, month));
    }

    @Test
    void eachAccountGetsOneInvoiceWithOneLinePerMeterAndAnExactTotal() {
        String a = id("a");
        String b = id("b");
        charge(a, "sms", 10, "0.5000", "2026-01-10T10:00:00Z");
        charge(a, "sms", 5, "0.2500", "2026-01-11T10:00:00Z");
        charge(a, "data-mb", 100, "1.0001", "2026-01-12T10:00:00Z");
        charge(b, "sms", 50, "0.0000", "2026-01-13T10:00:00Z"); // free usage still shows up
        charge(id("december"), "sms", 1, "0.05", "2025-12-31T23:59:59Z"); // not in January

        RunSummary summary = runs.run(month(2026, 1));

        assertThat(summary.created()).isEqualTo(2);
        Invoice invoiceA = invoices.find(a, month(2026, 1)).orElseThrow();
        assertThat(invoiceA.lines()).containsExactly(
                new InvoiceLine("data-mb", 100, Money.of("1.0001")),
                new InvoiceLine("sms", 15, Money.of("0.75")));
        assertThat(invoiceA.total()).isEqualTo(Money.of("1.7501"));
        assertThat(invoices.find(b, month(2026, 1)).orElseThrow().total()).isEqualTo(Money.ZERO);
    }

    @Test
    void runningTheSamePeriodTwiceCreatesNoDuplicates() {
        String a = id("a");
        String b = id("b");
        charge(a, "sms", 1, "0.05", "2026-02-05T10:00:00Z");
        charge(b, "sms", 1, "0.05", "2026-02-06T10:00:00Z");

        RunSummary first = runs.run(month(2026, 2));
        RunSummary second = runs.run(month(2026, 2));

        assertThat(first.created()).isEqualTo(2);
        assertThat(first.alreadyInvoiced()).isZero();
        assertThat(second.created()).isZero();
        assertThat(second.alreadyInvoiced()).isEqualTo(2);
        assertThat(count("invoices", "period_start = '2026-02-01T00:00:00Z'")).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM invoice_lines l JOIN invoices i ON i.id = l.invoice_id "
                + "WHERE i.period_start = '2026-02-01T00:00:00Z'", Integer.class)).isEqualTo(2);
    }

    @Test
    void thePeriodStartsAtTheFirstInstantOfTheMonthAndEndsBeforeTheNextOne() {
        String atStart = id("start");
        String atEnd = id("end");
        String nextMonth = id("next");
        charge(atStart, "sms", 1, "0.05", "2026-03-01T00:00:00Z");
        charge(atEnd, "sms", 1, "0.05", "2026-03-31T23:59:59.999Z");
        charge(nextMonth, "sms", 1, "0.05", "2026-04-01T00:00:00Z");

        runs.run(month(2026, 3));

        assertThat(invoices.find(atStart, month(2026, 3))).isPresent();
        assertThat(invoices.find(atEnd, month(2026, 3))).isPresent();
        assertThat(invoices.find(nextMonth, month(2026, 3))).isEmpty();
    }

    @Test
    void aChargeIsJudgedInUtcNotInTheLocalTimeOfTheUsage() {
        String turkey = id("turkey");
        String americas = id("americas");
        // 01:00 on 1 October in Turkey (UTC+3) is 22:00 on 30 September in UTC: September
        charge(turkey, "sms", 1, "0.05", "2026-10-01T01:00:00+03:00");
        // 23:30 on 30 September in UTC-5 is 04:30 on 1 October in UTC: October
        charge(americas, "sms", 1, "0.05", "2026-09-30T23:30:00-05:00");

        runs.run(month(2026, 9));

        assertThat(invoices.find(turkey, month(2026, 9))).isPresent();
        assertThat(invoices.find(americas, month(2026, 9))).isEmpty();
    }

    @Test
    void manyAccountsAreProcessedInBatches() {
        for (int i = 0; i < 5; i++) {
            charge(id("acc" + i), "sms", 1, "0.05", "2026-06-10T10:00:00Z");
        }

        RunSummary summary = runs.run(month(2026, 6)); // batch size is 2 in this test

        assertThat(summary.created()).isEqualTo(5);
        assertThat(count("invoices", "period_start = '2026-06-01T00:00:00Z'")).isEqualTo(5);
    }

    @Test
    void concurrentRunsOfTheSamePeriodStillCreateEachInvoiceOnce() throws Exception {
        for (int i = 0; i < 6; i++) {
            charge(id("acc" + i), "sms", 1, "0.05", "2026-07-10T10:00:00Z");
        }
        ExecutorService pool = Executors.newFixedThreadPool(4);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<RunSummary>> results = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            Callable<RunSummary> task = () -> {
                go.await();
                return runs.run(month(2026, 7));
            };
            results.add(pool.submit(task));
        }
        go.countDown();
        int created = 0;
        for (Future<RunSummary> f : results) {
            created += f.get().created();
        }
        pool.shutdown();

        assertThat(created).isEqualTo(6);
        assertThat(count("invoices", "period_start = '2026-07-01T00:00:00Z'")).isEqualTo(6);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM invoice_lines l JOIN invoices i ON i.id = l.invoice_id "
                + "WHERE i.period_start = '2026-07-01T00:00:00Z'", Integer.class)).isEqualTo(6);
    }

    @Test
    void aChargeThatArrivesAfterTheInvoiceIsNotAddedByARerun() {
        String a = id("a");
        charge(a, "sms", 1, "0.05", "2026-05-10T10:00:00Z");
        runs.run(month(2026, 5));

        charge(a, "sms", 1, "0.05", "2026-05-20T10:00:00Z"); // late: rated after the invoice was made
        RunSummary rerun = runs.run(month(2026, 5));

        // known limit, documented in ADR 0005: an issued invoice is never rewritten
        assertThat(rerun.created()).isZero();
        assertThat(invoices.find(a, month(2026, 5)).orElseThrow().total()).isEqualTo(Money.of("0.05"));
    }

    @Test
    void manyTinyChargesSumExactlyWithoutRoundingAgain() {
        String a = id("a");
        for (int i = 0; i < 50; i++) {
            charge(a, "kb", 1, "0.0001", "2026-11-10T10:00:00Z");
        }

        runs.run(month(2026, 11));

        assertThat(invoices.find(a, month(2026, 11)).orElseThrow().total()).isEqualTo(Money.of("0.005"));
    }

    @Test
    void aPeriodThatHasNotEndedCannotBeInvoiced() {
        assertThatThrownBy(() -> runs.run(month(2027, 6))).isInstanceOf(PeriodNotClosedException.class);
        assertThatThrownBy(() -> runs.run(month(2027, 7))).isInstanceOf(PeriodNotClosedException.class);
    }

    @Test
    void aMonthIsNotInvoicedWhileRatingIsBehindAndIsOnceItCatchesUp() {
        String account = "behind-" + UUID.randomUUID();
        charge(account, "sms", 1, "0.0500", "2026-08-10T10:00:00Z");
        RATING_CAUGHT_UP.set(false);
        try {
            assertThatThrownBy(() -> runs.run(month(2026, 8))).isInstanceOf(RatingNotCaughtUpException.class);
            assertThat(post("/v1/invoice-runs", "{\"period\":\"2026-08\"}").getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
            assertThat(invoices.find(account, month(2026, 8))).isEmpty();
        } finally {
            RATING_CAUGHT_UP.set(true);
        }

        assertThat(runs.run(month(2026, 8)).created()).isGreaterThanOrEqualTo(1);
        assertThat(invoices.find(account, month(2026, 8))).isPresent();
    }

    @Test
    void theHttpApiRunsAnInvoiceRunAndReadsTheInvoiceBack() {
        String a = id("http");
        charge(a, "sms", 10, "0.5000", "2026-08-10T10:00:00Z");
        charge(a, "data-mb", 100, "1.0001", "2026-08-11T10:00:00Z");

        ResponseEntity<String> run = post("/v1/invoice-runs", "{\"period\":\"2026-08\"}");
        ResponseEntity<String> invoice = http.getForEntity("/v1/invoices/" + a + "?period=2026-08", String.class);

        assertThat(run.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(run.getBody()).contains("\"period\":\"2026-08\"").contains("\"invoicesCreated\":1");
        assertThat(invoice.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(invoice.getBody()).contains("\"accountId\":\"" + a + "\"")
                .contains("\"total\":\"1.5001\"")
                .contains("\"meter\":\"data-mb\"");
        // a second call is a no-op
        assertThat(post("/v1/invoice-runs", "{\"period\":\"2026-08\"}").getBody())
                .contains("\"invoicesCreated\":0").contains("\"alreadyInvoiced\":1");
    }

    @Test
    void theHttpApiAnswersWithTheRightStatusForBadInput() {
        assertThat(post("/v1/invoice-runs", "{\"period\":\"2026-13\"}").getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(post("/v1/invoice-runs", "{\"period\":\"\"}").getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(post("/v1/invoice-runs", "{\"period\":\"2027-06\"}").getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(http.getForEntity("/v1/invoices/nobody?period=2026-08", String.class).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(http.getForEntity("/v1/invoices/nobody?period=oops", String.class).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void invoiceRunsAreCountedAndActuatorIsExposed() {
        String a = id("metric");
        charge(a, "sms", 1, "0.05", "2026-12-10T10:00:00Z");
        double createdBefore = meters.get("ratekit.invoices").tag("result", "created").counter().count();
        double skippedBefore = meters.get("ratekit.invoices").tag("result", "already_invoiced").counter().count();

        RunSummary first = runs.run(month(2026, 12));
        RunSummary second = runs.run(month(2026, 12));

        assertThat(meters.get("ratekit.invoices").tag("result", "created").counter().count() - createdBefore)
                .isEqualTo(first.created() + second.created());
        assertThat(meters.get("ratekit.invoices").tag("result", "already_invoiced").counter().count() - skippedBefore)
                .isEqualTo(first.alreadyInvoiced() + second.alreadyInvoiced());
        assertThat(first.created()).isEqualTo(1);
        assertThat(http.getForEntity("/actuator/health", String.class).getBody()).contains("UP");
        assertThat(http.getForEntity("/actuator/prometheus", String.class).getBody())
                .contains("ratekit_invoices_total").contains("application=\"ratekit-billing\"");
    }

    @Test
    void openApiDescribesTheEndpoints() {
        ResponseEntity<String> spec = http.getForEntity("/v3/api-docs", String.class);

        assertThat(spec.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(spec.getBody()).contains("/v1/invoice-runs").contains("/v1/invoices/{accountId}");
    }

    // ---- helpers ----

    private static String id(String prefix) {
        return prefix + "-" + UUID.randomUUID();
    }

    private void charge(String account, String meter, long quantity, String amount, String occurredAt) {
        jdbc.update("INSERT INTO charges (account_id, event_id, meter, quantity, amount, occurred_at) "
                        + "VALUES (?, ?, ?, ?, ?::numeric, ?::timestamptz)",
                account, UUID.randomUUID().toString(), meter, quantity, amount, occurredAt);
    }

    private int count(String table, String where) {
        return jdbc.queryForObject("SELECT count(*) FROM " + table + " WHERE " + where, Integer.class);
    }

    private ResponseEntity<String> post(String path, String body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return http.postForEntity(path, new HttpEntity<>(body, headers), String.class);
    }
}
