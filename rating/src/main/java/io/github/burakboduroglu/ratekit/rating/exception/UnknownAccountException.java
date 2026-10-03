package io.github.burakboduroglu.ratekit.rating.exception;

/** The event names an account that does not exist. Retrying cannot help; it is dead-lettered. */
public class UnknownAccountException extends RuntimeException {

    public UnknownAccountException(String accountId) {
        super("unknown account '" + accountId + "'");
    }
}
