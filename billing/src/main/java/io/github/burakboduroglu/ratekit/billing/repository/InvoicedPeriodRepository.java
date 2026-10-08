package io.github.burakboduroglu.ratekit.billing.repository;

import io.github.burakboduroglu.ratekit.common.BillingPeriod;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Months whose invoice run has completed; their unbilled charges are late (ADR 0020). */
@Repository
public class InvoicedPeriodRepository {

    private final JdbcClient jdbc;

    InvoicedPeriodRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public void markCompleted(BillingPeriod period) {
        jdbc.sql("INSERT INTO invoiced_periods (period_start) VALUES (?) ON CONFLICT DO NOTHING")
                .param(OffsetDateTime.ofInstant(period.start(), ZoneOffset.UTC))
                .update();
    }
}
