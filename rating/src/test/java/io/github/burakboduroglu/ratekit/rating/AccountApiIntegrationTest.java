package io.github.burakboduroglu.ratekit.rating;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.utility.DockerImageName;

/** The account and top-up API over HTTP, against a real PostgreSQL. Every test uses its own account. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class AccountApiIntegrationTest {

    @Container
    @ServiceConnection
    static final KafkaContainer KAFKA = new KafkaContainer(DockerImageName.parse("apache/kafka:4.3.1"));

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(DockerImageName.parse("postgres:17.11-alpine"));

    @Autowired
    TestRestTemplate http;

    @Autowired
    JdbcTemplate jdbc;

    @Test
    void aNewAccountStartsAtZeroAndCanBeRead() {
        String id = newId();

        ResponseEntity<String> created = post("/v1/accounts", "{\"accountId\":\"" + id + "\"}");
        ResponseEntity<String> read = http.getForEntity("/v1/accounts/" + id, String.class);

        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(read.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(read.getBody()).contains("\"balance\":\"0.0000\"");
    }

    @Test
    void openingTheSameAccountTwiceIsAConflict() {
        String id = account();

        assertThat(post("/v1/accounts", "{\"accountId\":\"" + id + "\"}").getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void anUnknownAccountIsNotFound() {
        assertThat(http.getForEntity("/v1/accounts/nobody-" + UUID.randomUUID(), String.class).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(topUp("nobody-" + UUID.randomUUID(), "t1", "\"5\"").getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void aTopUpIsCreditedAndRecordedInTheLedger() {
        String id = account();

        ResponseEntity<String> response = topUp(id, "t1", "\"10.50\"");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody()).contains("\"amount\":\"10.5000\"").contains("\"balance\":\"10.5000\"");
        assertThat(balance(id)).isEqualByComparingTo("10.5");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM top_ups WHERE account_id = ?", Integer.class, id)).isEqualTo(1);
    }

    @Test
    void retryingATopUpChangesNothingAndSaysSo() {
        String id = account();
        topUp(id, "t1", "\"10\"");

        ResponseEntity<String> retry = topUp(id, "t1", "10.0000");

        assertThat(retry.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(retry.getBody()).contains("\"balance\":\"10.0000\"");
        assertThat(balance(id)).isEqualByComparingTo("10");
    }

    @Test
    void reusingATopUpIdWithAnotherAmountIsAConflictAndChangesNothing() {
        String id = account();
        topUp(id, "t1", "\"10\"");

        ResponseEntity<String> reuse = topUp(id, "t1", "\"20\"");

        assertThat(reuse.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(reuse.getBody()).contains("10.0000").contains("20.0000");
        assertThat(balance(id)).isEqualByComparingTo("10");
    }

    @Test
    void anAmountThatIsZeroNegativeOrTooPreciseIsRefused() {
        String id = account();

        for (String amount : List.of("\"0\"", "\"-5\"", "\"0.00001\"", "null")) {
            assertThat(topUp(id, "t-" + UUID.randomUUID(), amount).getStatusCode())
                    .as("amount %s", amount).isEqualTo(HttpStatus.BAD_REQUEST);
        }
        assertThat(balance(id)).isEqualByComparingTo("0");
    }

    @Test
    void tenConcurrentRetriesOfOneTopUpCreditItOnce() throws Exception {
        String id = account();
        ExecutorService pool = Executors.newFixedThreadPool(10);
        List<Callable<HttpStatus>> calls = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            calls.add(() -> HttpStatus.valueOf(topUp(id, "same", "\"7\"").getStatusCode().value()));
        }

        List<HttpStatus> statuses = new ArrayList<>();
        for (Future<HttpStatus> f : pool.invokeAll(calls)) {
            statuses.add(f.get());
        }
        pool.shutdown();

        assertThat(statuses).filteredOn(s -> s == HttpStatus.CREATED).hasSize(1);
        assertThat(statuses).filteredOn(s -> s == HttpStatus.OK).hasSize(9);
        assertThat(balance(id)).isEqualByComparingTo("7");
    }

    // ---- helpers ----

    private String newId() {
        return "acc-" + UUID.randomUUID();
    }

    private String account() {
        String id = newId();
        assertThat(post("/v1/accounts", "{\"accountId\":\"" + id + "\"}").getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return id;
    }

    private ResponseEntity<String> topUp(String account, String topUpId, String amountJson) {
        return post("/v1/accounts/" + account + "/top-ups", "{\"topUpId\":\"" + topUpId + "\",\"amount\":" + amountJson + "}");
    }

    private ResponseEntity<String> post(String path, String body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return http.postForEntity(path, new HttpEntity<>(body, headers), String.class);
    }

    private BigDecimal balance(String account) {
        return jdbc.queryForObject("SELECT balance FROM accounts WHERE id = ?", BigDecimal.class, account);
    }
}
