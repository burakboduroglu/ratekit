package io.github.burakboduroglu.ratekit.common;

import java.time.Instant;
import java.util.Objects;

/**
 * A progress marker that rating's outbox relay writes to every partition of the {@code charges}
 * topic (ADR 0019): every charge rating committed before {@code publishedThrough} has already been
 * written to the topic, so on this partition it comes before this marker.
 *
 * @param publishedThrough a time on rating's database clock
 * @param partitions       how many partitions the topic had when the marker was written, so a reader
 *                         knows how many markers it must have seen
 */
public record ChargeFeedWatermark(Instant publishedThrough, int partitions) {

    public ChargeFeedWatermark {
        Objects.requireNonNull(publishedThrough, "publishedThrough");
        if (partitions <= 0) {
            throw new IllegalArgumentException("partitions must be positive, got " + partitions);
        }
    }
}
