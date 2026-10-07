package io.github.burakboduroglu.ratekit.rating.repository;

import io.github.burakboduroglu.ratekit.common.Money;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class TopUpRepository {

    private final JdbcClient jdbc;

    TopUpRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Records the top-up unless one with the same id already exists for the account.
     *
     * @return true if it was recorded now (the caller credits the balance), false if it is a retry
     */
    public boolean insertIfAbsent(String accountId, String topUpId, Money amount) {
        return jdbc.sql("INSERT INTO top_ups (account_id, top_up_id, amount) VALUES (?, ?, ?) ON CONFLICT DO NOTHING")
                .params(accountId, topUpId, amount.amount())
                .update() == 1;
    }

    public Optional<Money> amountOf(String accountId, String topUpId) {
        return jdbc.sql("SELECT amount FROM top_ups WHERE account_id = ? AND top_up_id = ?")
                .params(accountId, topUpId)
                .query((rs, row) -> new Money(rs.getBigDecimal("amount")))
                .optional();
    }
}
