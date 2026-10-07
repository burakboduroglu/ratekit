package io.github.burakboduroglu.ratekit.rating.exception;

public class AccountNotFoundException extends RuntimeException {

    public AccountNotFoundException(String accountId) {
        super("no account '" + accountId + "'");
    }
}
