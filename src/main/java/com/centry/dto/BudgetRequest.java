package com.centry.dto;

import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.math.BigDecimal;

/** Body of {@code PUT /api/v1/budgets/{category}}. */
public record BudgetRequest(

        // Two decimal places at most: money is stored as integer cents.
        @NotNull @Positive @Digits(integer = 9, fraction = 2) BigDecimal monthlyLimit,

        /** Percent of the limit at which the warning level starts; omitted means 80. */
        @Min(1) @Max(99) Integer warnPercent) {
}
