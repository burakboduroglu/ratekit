package io.github.burakboduroglu.ratekit.rating.repository;

import io.github.burakboduroglu.ratekit.common.Money;
import io.github.burakboduroglu.ratekit.rating.domain.Account;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class AccountRepository {

    private final JdbcClient jdbc;

    AccountRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** @return true if the account was created, false if one with this id already exists */
    public boolean create(String accountId) {
        return jdbc.sql("INSERT INTO accounts (id) VALUES (?) ON CONFLICT DO NOTHING")
                .param(accountId)
                .update() == 1;
    }

    public Optional<Account> find(String accountId) {
        return jdbc.sql("SELECT id, balance FROM accounts WHERE id = ?")
                .param(accountId)
                .query((rs, row) -> new Account(rs.getString("id"), new Money(rs.getBigDecimal("balance"))))
                .optional();
    }

    /** Adds {@code amount} in one statement, so it cannot lose a concurrent deduction. */
    public void credit(String accountId, Money amount) {
        jdbc.sql("UPDATE accounts SET balance = balance + ?, updated_at = now() WHERE id = ?")
                .params(amount.amount(), accountId)
                .update();
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
