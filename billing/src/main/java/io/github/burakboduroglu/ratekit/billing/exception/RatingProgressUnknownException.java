package io.github.burakboduroglu.ratekit.billing.exception;

import org.springframework.core.NestedExceptionUtils;

/** Kafka could not be asked how far rating is; the run is refused rather than risked. */
public class RatingProgressUnknownException extends RuntimeException {

    public RatingProgressUnknownException(Throwable cause) {
        super("could not check rating's progress in Kafka: " + describe(cause), cause);
    }

    // a Kafka timeout often carries no message; the root cause's type still says what happened
    private static String describe(Throwable cause) {
        Throwable root = NestedExceptionUtils.getMostSpecificCause(cause);
        return root.getMessage() != null ? root.getMessage() : root.getClass().getSimpleName();
    }
}
