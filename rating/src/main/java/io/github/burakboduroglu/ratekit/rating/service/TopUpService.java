package io.github.burakboduroglu.ratekit.rating.service;

import io.github.burakboduroglu.ratekit.common.Money;
import io.github.burakboduroglu.ratekit.rating.domain.Account;
import io.github.burakboduroglu.ratekit.rating.exception.AccountNotFoundException;
import io.github.burakboduroglu.ratekit.rating.exception.TopUpConflictException;
import io.github.burakboduroglu.ratekit.rating.repository.AccountRepository;
import io.github.burakboduroglu.ratekit.rating.repository.TopUpRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Adds money to a prepaid balance, at most once per top-up id.
 *
 * <p>The client chooses the top-up id. If its request times out it sends the same id again; the
 * primary key on {@code top_ups} turns that second attempt into a no-op, and the answer says so. The
 * ledger row and the balance change share one transaction, so neither exists without the other.
 */
@Service
public class TopUpService {

    /** What a top-up request did, and the balance after it. */
    public record Result(Account account, boolean created) {
    }

    private final AccountRepository accounts;
    private final TopUpRepository topUps;

    public TopUpService(AccountRepository accounts, TopUpRepository topUps) {
        this.accounts = accounts;
        this.topUps = topUps;
    }

    @Transactional
    public Result topUp(String accountId, String topUpId, Money amount) {
        if (!accounts.exists(accountId)) {
            throw new AccountNotFoundException(accountId);
        }
        boolean created = topUps.insertIfAbsent(accountId, topUpId, amount);
        if (created) {
            accounts.credit(accountId, amount);
        } else {
            Money recorded = topUps.amountOf(accountId, topUpId).orElseThrow();
            if (!recorded.equals(amount)) {
                throw new TopUpConflictException(topUpId, recorded, amount);
            }
        }
        return new Result(accounts.find(accountId).orElseThrow(), created);
    }
}
