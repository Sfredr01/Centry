package com.centry.notify;

import com.centry.model.MonthSummary;
import com.centry.model.MonthSummary.CategorySpend;
import com.centry.model.MonthSummary.MemberSpend;
import com.centry.model.MonthSummary.MerchantSpend;

import java.time.format.TextStyle;
import java.util.List;
import java.util.Locale;
import java.util.StringJoiner;
import java.util.function.Function;

/** Renders a month summary as a short plain-text notification that fits a phone screen. */
final class DigestMessage {

    private DigestMessage() {
    }

    static Notification of(MonthSummary summary) {
        String month = summary.month().getMonth().getDisplayName(TextStyle.FULL, Locale.ENGLISH) + " "
                + summary.month().getYear();
        return new Notification("Centry: " + month + " summary", body(summary, month),
                Notification.Priority.LOW, List.of("bar_chart"));
    }

    private static String body(MonthSummary summary, String month) {
        if (summary.totalCents() == 0) {
            return "No expenses recorded in " + month;
        }
        StringJoiner out = new StringJoiner("\n\n");
        out.add("Total spent: " + dollars(summary.totalCents()));

        // Non-empty month: with no budgets all spend is unbudgeted, so this block is never empty.
        StringJoiner budgets = new StringJoiner("\n");
        if (!summary.budgeted().isEmpty()) {
            budgets.add("Budgets");
            summary.budgeted().forEach(c -> budgets.add(category(c)));
        }
        if (summary.unbudgetedCents() > 0) {
            budgets.add("Not budgeted " + dollars(summary.unbudgetedCents()));
        }
        out.add(budgets.toString());
        section(out, "Top merchants", summary.topMerchants(), m -> m.merchant() + " " + dollars(m.cents()));
        section(out, "By member", summary.byMember(), m -> m.member() + " " + dollars(m.cents()));
        return out.toString();
    }

    private static <T> void section(StringJoiner out, String heading, List<T> rows, Function<T, String> line) {
        if (!rows.isEmpty()) {
            out.add(heading + "\n" + String.join("\n", rows.stream().map(line).toList()));
        }
    }

    private static String category(CategorySpend c) {
        String name = Character.toUpperCase(c.category().charAt(0)) + c.category().substring(1);
        return name + " " + dollars(c.spentCents()) + " / " + limit(c.limitCents())
                + " (" + c.spentCents() * 100 / c.limitCents() + "%)";
    }

    /** Whole-dollar limits drop the cents: "$600", not "$600.00". */
    private static String limit(long cents) {
        return cents % 100 == 0 ? String.format(Locale.ROOT, "$%,d", cents / 100) : dollars(cents);
    }

    private static String dollars(long cents) {
        return String.format(Locale.ROOT, "$%,d.%02d", cents / 100, cents % 100);
    }
}
