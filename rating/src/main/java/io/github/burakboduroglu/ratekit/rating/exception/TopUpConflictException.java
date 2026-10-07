package io.github.burakboduroglu.ratekit.rating.exception;

import io.github.burakboduroglu.ratekit.common.Money;

/** A top-up id was reused with a different amount: it is not a retry, so it is refused rather than guessed at. */
public class TopUpConflictException extends RuntimeException {

    public TopUpConflictException(String topUpId, Money recorded, Money requested) {
        super("top-up '" + topUpId + "' was already recorded with amount " + recorded + ", not " + requested);
    }
}
