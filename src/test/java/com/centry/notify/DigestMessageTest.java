package com.centry.notify;

import com.centry.model.MonthSummary;
import com.centry.model.MonthSummary.CategorySpend;
import com.centry.model.MonthSummary.MemberSpend;
import org.junit.jupiter.api.Test;

import java.time.YearMonth;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class DigestMessageTest {

    @Test
    void spendWithoutMerchantsOrBudgetOverrunsStillReads() {
        MonthSummary summary = new MonthSummary(YearMonth.of(2026, 3), 12000,
                List.of(new CategorySpend("food", 12000, 10000)), 0, List.of(),
                List.of(new MemberSpend("Ann", 12000)));

        assertThat(DigestMessage.of(summary).body()).isEqualTo("""
                Total spent: $120.00

                Budgets
                Food $120.00 / $100 (120%)

                By member
                Ann $120.00""");
    }
}
