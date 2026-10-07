package io.github.burakboduroglu.ratekit.billing.service;

import java.time.Instant;

/** Whether rating has finished the usage that was accepted before a moment. */
public interface RatingProgress {

    /** @return true if every usage event written before {@code cutoff} has been rated (or dead-lettered) */
    boolean caughtUpTo(Instant cutoff);
}
