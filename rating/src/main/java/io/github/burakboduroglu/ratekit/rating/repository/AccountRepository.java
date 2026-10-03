package io.github.burakboduroglu.ratekit.rating.repository;

import io.github.burakboduroglu.ratekit.common.Money;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class AccountRepository {

    private final JdbcClient jdbc;

    AccountRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public boolean exists(String accountId) {
        return jdbc.sql("SELECT EXISTS (SELECT 1 FROM accounts WHERE id = ?)")
                .param(accountId)
                .query(Boolean.class)
                .single();
    }

    /**
     * Takes {@code amount} from the balance only if the account can afford it.
     *
     * <p>One statement does both the check and the subtraction, so two concurrent callers cannot
     * both see the same old balance (the lost update of read-then-write). PostgreSQL locks the row
     * for the statement: the second caller waits, then re-checks against the new balance.
     *
     * @return true if the amount was taken, false if the balance was too low (nothing changed)
     */
    public boolean tryDeduct(String accountId, Money amount) {
        int updated = jdbc.sql("UPDATE accounts SET balance = balance - ?, updated_at = now() "
                        + "WHERE id = ? AND balance >= ?")
                .params(amount.amount(), accountId, amount.amount())
                .update();
        return updated == 1;
    }
}
