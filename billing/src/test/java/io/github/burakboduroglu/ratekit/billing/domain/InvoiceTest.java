package io.github.burakboduroglu.ratekit.billing.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.burakboduroglu.ratekit.common.BillingPeriod;
import io.github.burakboduroglu.ratekit.common.Money;
import java.time.YearMonth;
import java.util.List;
import org.junit.jupiter.api.Test;

class InvoiceTest {

    private final BillingPeriod period = BillingPeriod.of(YearMonth.of(2026, 9));

    @Test
    void theTotalIsTheExactSumOfTheLinesAndTheLinesAreOrderedByMeter() {
        Invoice invoice = Invoice.of("acc-1", period, List.of(
                new InvoiceLine("sms", 15, Money.of("0.75")),
                new InvoiceLine("data-mb", 100, Money.of("1.0001"))));

        assertThat(invoice.total()).isEqualTo(Money.of("1.7501"));
        assertThat(invoice.lines()).extracting(InvoiceLine::meter).containsExactly("data-mb", "sms");
    }

    @Test
    void tinyAmountsAddUpWithoutFloatingPointDrift() {
        List<InvoiceLine> lines = java.util.stream.IntStream.range(0, 10)
                .mapToObj(i -> new InvoiceLine("m" + i, 1, Money.of("0.1")))
                .toList();

        assertThat(Invoice.of("acc-1", period, lines).total()).isEqualTo(Money.of("1"));
    }

    @Test
    void anInvoiceNeedsAtLeastOneLine() {
        assertThatThrownBy(() -> Invoice.of("acc-1", period, List.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aTotalThatDisagreesWithTheLinesIsRejected() {
        assertThatThrownBy(() -> new Invoice("acc-1", period,
                List.of(new InvoiceLine("sms", 1, Money.of("1"))), Money.of("2")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("does not equal");
    }

    @Test
    void aLineRejectsBadValues() {
        assertThatThrownBy(() -> new InvoiceLine(" ", 1, Money.ZERO)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new InvoiceLine("sms", 0, Money.ZERO)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new InvoiceLine("sms", 1, Money.of("-0.0001"))).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aFreeLineWithZeroAmountIsValid() {
        assertThat(Invoice.of("acc-1", period, List.of(new InvoiceLine("sms", 50, Money.ZERO))).total())
                .isEqualTo(Money.ZERO);
    }
}
