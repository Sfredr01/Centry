package com.centry.repository;

import com.centry.model.MonthSummary;
import com.centry.model.MonthSummary.CategorySpend;
import com.centry.model.MonthSummary.MemberSpend;
import com.centry.model.MonthSummary.MerchantSpend;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.time.YearMonth;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Read-only aggregates behind the monthly digest; the month bounds are the ones budgets use. */
@Repository
public class DigestQuery {

    private static final int TOP_MERCHANTS = 3;

    private final JdbcClient jdbc;

    public DigestQuery(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public MonthSummary forMonth(YearMonth month) {
        String from = Cents.monthStart(month);
        String to = Cents.monthEnd(month);

        long total = jdbc.sql("""
                        SELECT COALESCE(SUM(amount_cents), 0) FROM expense
                         WHERE occurred_at >= ? AND occurred_at < ?""")
                .params(from, to).query(Long.class).single();

        // Spend by normalized category (NULL category groups under NULL), then matched to the budgets in memory.
        Map<String, Long> spentByCategory = new HashMap<>();
        jdbc.sql("""
                        SELECT lower(trim(category)) AS key, SUM(amount_cents) AS cents FROM expense
                         WHERE occurred_at >= ? AND occurred_at < ? GROUP BY key""")
                .params(from, to)
                .query((rs, row) -> spentByCategory.put(rs.getString("key"), rs.getLong("cents")))
                .list();
        List<CategorySpend> budgeted = jdbc.sql(
                        "SELECT category_key, monthly_limit_cents FROM budget ORDER BY category_key")
                .query((rs, row) -> new CategorySpend(rs.getString("category_key"),
                        spentByCategory.getOrDefault(rs.getString("category_key"), 0L),
                        rs.getLong("monthly_limit_cents")))
                .list();
        long budgetedTotal = budgeted.stream().mapToLong(CategorySpend::spentCents).sum();

        List<MerchantSpend> topMerchants = jdbc.sql("""
                        SELECT MIN(trim(merchant)) AS merchant, SUM(amount_cents) AS cents FROM expense
                         WHERE occurred_at >= ? AND occurred_at < ? AND trim(COALESCE(merchant, '')) <> ''
                         GROUP BY lower(trim(merchant))
                         ORDER BY cents DESC, lower(trim(merchant)) LIMIT ?""")
                .params(from, to, TOP_MERCHANTS)
                .query((rs, row) -> new MerchantSpend(rs.getString("merchant"), rs.getLong("cents")))
                .list();

        List<MemberSpend> byMember = jdbc.sql("""
                        SELECT COALESCE(u.display_name, 'Unassigned') AS member, SUM(e.amount_cents) AS cents
                          FROM expense e LEFT JOIN users u ON u.id = e.spent_by
                         WHERE e.occurred_at >= ? AND e.occurred_at < ?
                         GROUP BY e.spent_by
                         ORDER BY cents DESC, member""")
                .params(from, to)
                .query((rs, row) -> new MemberSpend(rs.getString("member"), rs.getLong("cents")))
                .list();

        return new MonthSummary(month, total, budgeted, total - budgetedTotal, topMerchants, byMember);
    }
}
