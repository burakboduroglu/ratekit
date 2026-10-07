package io.github.burakboduroglu.ratekit.rating.service;

import io.github.burakboduroglu.ratekit.rating.domain.Account;
import io.github.burakboduroglu.ratekit.rating.exception.AccountAlreadyExistsException;
import io.github.burakboduroglu.ratekit.rating.exception.AccountNotFoundException;
import io.github.burakboduroglu.ratekit.rating.repository.AccountRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Opens accounts and reads their balance. A new account starts at zero; money comes in through top-ups. */
@Service
public class AccountService {

    private final AccountRepository accounts;

    public AccountService(AccountRepository accounts) {
        this.accounts = accounts;
    }

    @Transactional
    public Account create(String accountId) {
        if (!accounts.create(accountId)) {
            throw new AccountAlreadyExistsException(accountId);
        }
        return find(accountId);
    }

    @Transactional(readOnly = true)
    public Account find(String accountId) {
        return accounts.find(accountId).orElseThrow(() -> new AccountNotFoundException(accountId));
    }
}
