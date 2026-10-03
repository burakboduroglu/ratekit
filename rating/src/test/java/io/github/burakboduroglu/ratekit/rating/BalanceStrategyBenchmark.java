package io.github.burakboduroglu.ratekit.rating;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.IntConsumer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * Compares ways to deduct a prepaid balance (ADR 0002) under contention: the atomic single
 * statement the service uses, SELECT ... FOR UPDATE, and optimistic locking with a version column.
 *
 * <p>This is a measurement, not a test of correctness, so it is off by default:
 * {@code mvn -pl rating test -Dtest=BalanceStrategyBenchmark -Dratekit.bench=true}. Results go to
 * stdout and to {@code rating/target/bench-balance.txt}. Numbers are only comparable with each
 * other on the same machine.
 */
@EnabledIfSystemProperty(named = "ratekit.bench", matches = "true")
@SpringBootTest(properties = {
        "spring.kafka.listener.auto-startup=false",
        "spring.datasource.hikari.maximum-pool-size=16"})
@Testcontainers
class BalanceStrategyBenchmark {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(DockerImageName.parse("postgres:17.11-alpine"));

    private static final int THREADS = 16;
    private static final int SECONDS = 6;
    private static final BigDecimal AMOUNT = new BigDecimal("0.0001");

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    TransactionTemplate tx;

    @Test
    void compareStrategiesOnOneHotAccountAndOnManyAccounts() throws Exception {
        jdbc.execute("ALTER TABLE accounts ADD COLUMN IF NOT EXISTS version BIGINT NOT NULL DEFAULT 0");
        List<String> report = new ArrayList<>();
        report.add(String.format("%-22s %-14s %12s %12s", "strategy", "accounts", "ops/s", "retries"));
        for (int accounts : new int[] {1, 200}) {
            seed(accounts);
            AtomicLong retries = new AtomicLong();
            report.add(row("atomic UPDATE", accounts, run(accounts, this::atomic), 0));
            seed(accounts);
            report.add(row("SELECT FOR UPDATE", accounts, run(accounts, this::selectForUpdate), 0));
            seed(accounts);
            report.add(row("optimistic (version)", accounts, run(accounts, i -> optimistic(i, retries)), retries.get()));
        }
        String text = String.join("\n", report);
        System.out.println("\nBALANCE STRATEGY BENCHMARK (" + THREADS + " threads, " + SECONDS + " s each)\n" + text);
        Files.writeString(Path.of("target/bench-balance.txt"), text + "\n");
    }

    private static String row(String name, int accounts, double opsPerSecond, long retries) {
        return String.format("%-22s %-14s %12.0f %12d", name, accounts == 1 ? "1 (hot)" : accounts + " (spread)", opsPerSecond, retries);
    }

    private void seed(int accounts) {
        jdbc.update("DELETE FROM accounts WHERE id LIKE 'bench-%'");
        for (int i = 0; i < accounts; i++) {
            jdbc.update("INSERT INTO accounts (id, balance) VALUES (?, 1000000000)", "bench-" + i);
        }
    }

    /** One statement: check and subtract together (what AccountRepository.tryDeduct does). */
    private void atomic(int account) {
        jdbc.update("UPDATE accounts SET balance = balance - ? WHERE id = ? AND balance >= ?",
                AMOUNT, "bench-" + account, AMOUNT);
    }

    /** Lock the row, read, compute in Java, write back, commit. */
    private void selectForUpdate(int account) {
        tx.executeWithoutResult(status -> {
            BigDecimal balance = jdbc.queryForObject(
                    "SELECT balance FROM accounts WHERE id = ? FOR UPDATE", BigDecimal.class, "bench-" + account);
            if (balance.compareTo(AMOUNT) >= 0) {
                jdbc.update("UPDATE accounts SET balance = ? WHERE id = ?", balance.subtract(AMOUNT), "bench-" + account);
            }
        });
    }

    /** Read with the version, write only if the version is unchanged, otherwise retry. */
    private void optimistic(int account, AtomicLong retries) {
        while (true) {
            var row = jdbc.queryForMap("SELECT balance, version FROM accounts WHERE id = ?", "bench-" + account);
            int updated = jdbc.update("UPDATE accounts SET balance = ?, version = version + 1 WHERE id = ? AND version = ?",
                    ((BigDecimal) row.get("balance")).subtract(AMOUNT), "bench-" + account, row.get("version"));
            if (updated == 1) {
                return;
            }
            retries.incrementAndGet();
        }
    }

    /** Runs the operation from THREADS threads for SECONDS seconds and returns operations per second. */
    private double run(int accounts, IntConsumer operation) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(THREADS);
        CountDownLatch go = new CountDownLatch(1);
        AtomicLong done = new AtomicLong();
        long[] deadline = new long[1];
        List<Future<?>> futures = new ArrayList<>();
        for (int t = 0; t < THREADS; t++) {
            int thread = t;
            futures.add(pool.submit(() -> {
                go.await();
                int i = thread;
                while (System.nanoTime() < deadline[0]) {
                    operation.accept(i++ % accounts);
                    done.incrementAndGet();
                }
                return null;
            }));
        }
        long start = System.nanoTime();
        deadline[0] = start + TimeUnit.SECONDS.toNanos(SECONDS);
        go.countDown();
        for (Future<?> f : futures) {
            f.get();
        }
        pool.shutdown();
        return done.get() / ((System.nanoTime() - start) / 1e9);
    }
}
