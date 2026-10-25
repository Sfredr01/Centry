package com.centry.model;

import java.math.BigDecimal;

/** A budget and what has been spent against it in one month. Money has two decimals. */
public record BudgetStatus(
        String category,
        BigDecimal monthlyLimit,
        int warnPercent,
        BigDecimal warningAmount,
        BigDecimal spent,
        BudgetLevel level) {

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
