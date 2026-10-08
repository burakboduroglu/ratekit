package io.github.burakboduroglu.ratekit.billing.service;

import java.time.Instant;

/** Whether the charges rating stored have reached billing's own table. */
public interface ChargeFeedProgress {

    /** @return true if every charge rating committed before {@code ratedBy} is in billing's table */
    boolean deliveredUpTo(Instant ratedBy);
}
