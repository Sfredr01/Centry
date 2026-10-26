package com.centry.model;

import java.time.YearMonth;
import java.util.List;

/** What a household spent in one month, in cents. A month without expenses has a zero total. */
public record MonthSummary(
        YearMonth month,
        long totalCents,
        List<CategorySpend> budgeted,
        long unbudgetedCents,
        List<MerchantSpend> topMerchants,
        List<MemberSpend> byMember) {

    /** Spend against one budget; {@code category} is the normalized budget key. */
    public record CategorySpend(String category, long spentCents, long limitCents) {
    }

    public record MerchantSpend(String merchant, long cents) {
    }

    /** {@code member} is the display name, or "Unassigned" for expenses without spent_by. */
    public record MemberSpend(String member, long cents) {
    }
}
