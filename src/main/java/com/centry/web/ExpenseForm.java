package com.centry.web;

import java.time.LocalDate;
import java.util.UUID;

/**
 * What an expense form (quick-add or edit) submits, as plain text so a bad value can be shown back to the user as typed.
 * Missing fields arrive as null and are treated as empty.
 *
 * @param clientRef generated when the form is rendered and used as the expense's transaction id, so a
 *                  double-tapped Save (or a retried request) stores one row
 */
record ExpenseForm(String amount, String category, String merchant, String date, String clientRef) {

    /** An empty form for a new expense dated {@code today}. */
    static ExpenseForm blank(LocalDate today, String category) {
        return new ExpenseForm("", category, "", today.toString(), UUID.randomUUID().toString());
    }

    ExpenseForm trimmed() {
        return new ExpenseForm(clean(amount), clean(category), clean(merchant), clean(date), clean(clientRef));
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }
}
