package io.github.burakboduroglu.ratekit.common;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class MoneyTest {

    @Test
    void alwaysHasScaleFour() {
        assertEquals(4, Money.of("1").amount().scale());
        assertEquals(4, Money.of("1.123456789").amount().scale());
    }

    @Test
    void equalValuesAreEqualRegardlessOfInputScale() {
        assertEquals(Money.of("1"), Money.of("1.0000"));
    }

    @Test
    void roundsHalfEvenAtTheBoundary() {
        // exactly half goes to the even neighbour
        assertEquals(Money.of("0.0000"), Money.of("0.00005"));
        assertEquals(Money.of("0.0002"), Money.of("0.00015"));
        assertEquals(Money.of("0.0002"), Money.of("0.00025"));
        // just above and below half are not ties
        assertEquals(Money.of("0.0001"), Money.of("0.000051"));
        assertEquals(Money.of("0.0000"), Money.of("0.000049"));
    }

    @Test
    void timesRoundsOnceAfterMultiplying() {
        // a unit price is a plain BigDecimal rate with full precision, not Money:
        // 3 units at 0.00005 = 0.00015 exactly, then rounded once to 0.0002
        assertEquals(Money.of("0.0002"), Money.of("1").times(new BigDecimal("0.00015")));
        assertEquals(Money.of("1.2500"), Money.of("0.5").times(new BigDecimal("2.5")));
        assertEquals(Money.of("0.0003"), Money.of("0.0001").times(3));
    }

    @Test
    void sumsAreExact() {
        Money sum = Money.ZERO;
        for (int i = 0; i < 10; i++) {
            sum = sum.plus(Money.of("0.1"));
        }
        assertEquals(Money.of("1"), sum);
    }

    @Test
    void minusCanGoNegativeAndReportsIt() {
        Money result = Money.of("1").minus(Money.of("1.5"));
        assertTrue(result.isNegative());
        assertFalse(Money.ZERO.isNegative());
    }

    @Test
    void comparesByValue() {
        assertTrue(Money.of("2").compareTo(Money.of("1.9999")) > 0);
        assertEquals(0, Money.of("1.0").compareTo(Money.of("1.0000")));
    }
}
