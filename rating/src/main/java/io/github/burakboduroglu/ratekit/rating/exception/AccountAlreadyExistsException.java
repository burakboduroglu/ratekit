package io.github.burakboduroglu.ratekit.rating.exception;

public class AccountAlreadyExistsException extends RuntimeException {

    public AccountAlreadyExistsException(String accountId) {
        super("account '" + accountId + "' already exists");
    }
}
