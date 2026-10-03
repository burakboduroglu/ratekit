package io.github.burakboduroglu.ratekit.rating.domain;

import java.math.BigDecimal;
import java.util.List;

/**
 * Graduated tiers: each unit is priced by the tier it falls into, like income-tax brackets.
 *
 * <p>With tiers {@code (100 @ 0.10), (unbounded @ 0.05)}, units 1 to 100 cost 0.10 each and unit
 * 101 onwards costs 0.05 each. Bounds are cumulative within the period and the last tier has no
 * upper bound.
 */
public final class TieredPrice implements PriceModel {

    /** @param upTo cumulative upper bound of this tier (inclusive); {@code null} means unbounded */
    public record Tier(Long upTo, BigDecimal rate) {

        public Tier {
            PriceModel.requireValidRate(rate);
            if (upTo != null && upTo <= 0) {
                throw new IllegalArgumentException("tier bound must be positive, got " + upTo);
            }
        }
    }

    private final List<Tier> tiers;

    public TieredPrice(List<Tier> tiers) {
        if (tiers == null || tiers.isEmpty()) {
            throw new IllegalArgumentException("at least one tier is required");
        }
        for (int i = 0; i < tiers.size(); i++) {
            boolean last = i == tiers.size() - 1;
            Long bound = tiers.get(i).upTo();
            if (last && bound != null) {
                throw new IllegalArgumentException("the last tier must be unbounded");
            }
            if (!last && bound == null) {
                throw new IllegalArgumentException("only the last tier may be unbounded");
            }
            if (!last && i > 0 && bound <= tiers.get(i - 1).upTo()) {
                throw new IllegalArgumentException("tier bounds must be strictly ascending");
            }
        }
        this.tiers = List.copyOf(tiers);
    }

    @Override
    public BigDecimal cost(long units) {
        PriceModel.requireNonNegative(units);
        BigDecimal total = BigDecimal.ZERO;
        long previousBound = 0;
        for (Tier tier : tiers) {
            if (units <= previousBound) {
                break;
            }
            long top = tier.upTo() == null ? Long.MAX_VALUE : tier.upTo();
            long unitsInTier = Math.min(units, top) - previousBound;
            total = total.add(tier.rate().multiply(BigDecimal.valueOf(unitsInTier)));
            previousBound = top;
        }
        return total;
    }
}
