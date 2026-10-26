package com.centry.model;

import java.math.BigDecimal;
import java.util.Comparator;

/** A budget and what has been spent against it in one month. Money has two decimals. */
public record BudgetStatus(
        String category,
        BigDecimal monthlyLimit,
        int warnPercent,
        BigDecimal warningAmount,
        BigDecimal spent,
        BudgetLevel level) {

    /**
     * Most urgent first: over limit, then near limit, then on track; within a level the highest spent/limit
     * ratio first (compared by cross-multiplying, so no division or rounding); ties by category key.
     * Relies on BudgetLevel's declaration order, mildest first.
     */
    public static final Comparator<BudgetStatus> WORST_FIRST =
            Comparator.comparing(BudgetStatus::level, Comparator.reverseOrder())
                    .thenComparing((a, b) -> b.spent().multiply(a.monthlyLimit())
                            .compareTo(a.spent().multiply(b.monthlyLimit())))
                    .thenComparing(BudgetStatus::category);

    /**
     * Integer-cent math throughout. The warning amount is {@code limit * warnPercent / 100} rounded up to a
     * whole cent, which is exactly "spent >= limit * warnPercent / 100" for whole-cent spend. Both thresholds
     * are inclusive ({@code >=}); the limit level wins when the rounded warning amount reaches the limit.
     */
    public static BudgetStatus of(String category, long limitCents, int warnPercent, long spentCents) {
        long warningCents = (limitCents * warnPercent + 99) / 100;
        BudgetLevel level = spentCents >= limitCents ? BudgetLevel.LIMIT_EXCEEDED
                : spentCents >= warningCents ? BudgetLevel.WARNING_EXCEEDED
                : BudgetLevel.OK;
        return new BudgetStatus(category, BigDecimal.valueOf(limitCents, 2), warnPercent,
                BigDecimal.valueOf(warningCents, 2), BigDecimal.valueOf(spentCents, 2), level);
    }
}
