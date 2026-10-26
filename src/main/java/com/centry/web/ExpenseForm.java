package com.centry.web;

import com.centry.entity.ExpenseEntity;

import java.time.LocalDate;
import java.util.UUID;

/**
 * What an expense form (quick-add or edit) submits, as plain text so a bad value can be shown back to the user as typed.
 * Missing fields arrive as null and are treated as empty.
 *
 * @param spentBy   the chosen household member's user id as text; blank means the person saving
 * @param clientRef generated when the form is rendered and used as the expense's transaction id, so a
 *                  double-tapped Save (or a retried request) stores one row
 */
record ExpenseForm(String amount, String category, String merchant, String date, String spentBy,
                   String clientRef) {

    /** An empty form for a new expense dated {@code today}, attributed to {@code spentBy} (a user id as text). */
    static ExpenseForm blank(LocalDate today, String category, String spentBy) {
        return new ExpenseForm("", category, "", today.toString(), spentBy, UUID.randomUUID().toString());
    }

    /** The edit form for a stored expense. Unattributed means a blank spender; there is no client reference. */
    static ExpenseForm of(ExpenseEntity expense) {
        return new ExpenseForm(expense.amount().toPlainString(), expense.category(), expense.merchant(),
                expense.timestamp().toLocalDate().toString(),
                expense.spentBy() == null ? "" : expense.spentBy().toString(), "");
    }

    ExpenseForm trimmed() {
        return new ExpenseForm(clean(amount), clean(category), clean(merchant), clean(date), clean(spentBy),
                clean(clientRef));
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }
}
