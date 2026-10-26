package com.centry.web;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;

class MoneyTest {

    private final Money money = new Money();

    @Test
    void formatsDollarsWithGroupingAndTwoDecimals() {
        assertThat(money.format(new BigDecimal("97.50"))).isEqualTo("$97.50");
        assertThat(money.format(new BigDecimal("1234.00"))).isEqualTo("$1,234.00");
        assertThat(money.format(new BigDecimal("1234567.89"))).isEqualTo("$1,234,567.89");
        assertThat(money.format(new BigDecimal("0.00"))).isEqualTo("$0.00");
    }

    @Test
    void ignoresTheServerLocale() {
        Locale original = Locale.getDefault();
        Locale.setDefault(Locale.GERMANY);
        try {
            assertThat(money.format(new BigDecimal("1234.50"))).isEqualTo("$1,234.50");
        } finally {
            Locale.setDefault(original);
        }
    }
}
