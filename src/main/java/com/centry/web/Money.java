package com.centry.web;

import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.text.NumberFormat;
import java.util.Locale;

/** How the browser UI shows money: US dollars, grouped, two decimals ("$1,234.50"). Never the server's locale. */
@Component("money")
public class Money {

    public String format(BigDecimal amount) {
        // A new instance per call: NumberFormat is not thread-safe. Locale.US pins "$", "," and ".".
        return NumberFormat.getCurrencyInstance(Locale.US).format(amount);
    }
}
