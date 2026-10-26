package com.centry.web;

import com.centry.entity.ExpenseEntity;
import com.centry.repository.BudgetRepository;
import com.centry.model.Role;
import com.centry.repository.ExpenseRepository;
import com.centry.repository.UserRepository;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// "Now" is 15 March 2026 (FixedClockConfig).
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(FixedClockConfig.class)
class DashboardPageTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ExpenseRepository expenses;

    @Autowired
    private BudgetRepository budgets;

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private UserRepository users;

    private long mom;
    private long kid;

    @BeforeEach
    void clean() {
        expenses.deleteAll();
        jdbc.sql("DELETE FROM budget").update();
        jdbc.sql("DELETE FROM users WHERE username <> 'admin'").update();
        LocalDateTime joined = LocalDateTime.of(2026, 1, 1, 0, 0);
        mom = users.insert("mom", "Mom", null, Role.MEMBER, joined).id();
        kid = users.insert("kid", "Kid", null, Role.MEMBER, joined).id();
    }

    private void spendBy(Long spender, String merchant, String category, String amount, LocalDateTime at) {
        expenses.save(new ExpenseEntity(null, UUID.randomUUID().toString(), merchant, new BigDecimal(amount),
                category, at, spender, null));
    }

    private Document dashboard(String url) throws Exception {
        return Html.of(mockMvc.perform(get(url).with(user("mom").roles("MEMBER"))).andExpect(status().isOk()));
    }

    private void spend(String merchant, String category, String amount, LocalDateTime at) {
        expenses.save(new ExpenseEntity(null, UUID.randomUUID().toString(), merchant, new BigDecimal(amount),
                category, at, null, null));
    }

    private Document dashboard() throws Exception {
        return Html.of(mockMvc.perform(get("/").with(user("mom").roles("MEMBER"))).andExpect(status().isOk()));
    }

    @Test
    void showsBudgetBarsForCurrentMonth() throws Exception {
        budgets.upsert("food", new BigDecimal("100.00"), 80);
        spend("Market", "Food", "85.00", LocalDateTime.of(2026, 3, 2, 9, 0));
        // February does not count toward March.
        spend("Market", "Food", "40.00", LocalDateTime.of(2026, 2, 28, 23, 59));
        // No budget for this category, so no bar for it.
        spend("Cinema", "fun", "12.00", LocalDateTime.of(2026, 3, 3, 20, 0));

        Document page = dashboard();

        assertThat(page.select("progress")).hasSize(1);
        Element bar = page.selectFirst("progress");
        assertThat(bar.attr("value")).isEqualTo("85.00");
        assertThat(bar.attr("max")).isEqualTo("100.00");
        assertThat(bar.attr("data-level")).isEqualTo("WARNING_EXCEEDED");
        assertThat(bar.parent().text()).contains("food").contains("85.00").contains("100.00");
    }

    @Test
    void showsRecentExpensesNewestFirstMax20() throws Exception {
        for (int day = 1; day <= 25; day++) {
            // 25 expenses in March, inserted oldest first; the day number doubles as the amount.
            spend(String.format("shop-%02d", day), "misc", day + ".00", LocalDateTime.of(2026, 3, day, 12, 0));
        }

        var rows = dashboard().select("#recent-expenses li.expense");

        assertThat(rows).hasSize(20);
        assertThat(rows.get(0).text()).contains("Mar 25").contains("shop-25").contains("misc").contains("25.00");
        assertThat(rows.get(19).text()).contains("shop-06");
    }

    @Test
    void monthSummaryShowsTotalSpentAgainstEveryBudget() throws Exception {
        budgets.upsert("food", new BigDecimal("100.00"), 80);
        budgets.upsert("fun", new BigDecimal("50.00"), 80);
        spendBy(mom, "Market", "Food", "85.00", MARCH_2);
        // Unbudgeted spend still counts toward the month.
        spendBy(kid, "Hardware", "home", "12.50", MARCH_2);
        spendBy(kid, "Market", "Food", "40.00", LocalDateTime.of(2026, 2, 28, 23, 59));

        Element summary = dashboard("/").selectFirst("#month-summary");
        assertThat(summary.select(".amount").text()).isEqualTo("$97.50");
        assertThat(summary.text()).contains("March").contains("of $150.00 budgeted");

        // Scoped to one member like the rest of the page.
        assertThat(dashboard("/?member=" + kid).select("#month-summary .amount").text()).isEqualTo("$12.50");
    }

    @Test
    void monthSummaryLeavesOutTheBudgetedTotalWhenThereAreNoBudgets() throws Exception {
        spend("Market", "Food", "5.00", MARCH_2);

        Element summary = dashboard().selectFirst("#month-summary");
        assertThat(summary.select(".amount").text()).isEqualTo("$5.00");
        assertThat(summary.text()).doesNotContain("budgeted");
    }

    @Test
    void budgetBarsSayTheStateAndWhatIsLeftInWords() throws Exception {
        budgets.upsert("food", new BigDecimal("100.00"), 80);
        budgets.upsert("fun", new BigDecimal("50.00"), 80);
        budgets.upsert("home", new BigDecimal("200.00"), 80);
        spend("Market", "Food", "85.00", MARCH_2);
        spend("Cinema", "fun", "62.75", MARCH_2);
        spend("Hardware", "home", "10.00", MARCH_2);

        var bars = dashboard().select("#budget-bars .budget-bar");

        assertThat(bars.eachText()).hasSize(3);
        // Worst first: over limit, near limit, on track.
        assertThat(bars.get(0).select(".pill[data-level=LIMIT_EXCEEDED]").text()).isEqualTo("Over limit");
        assertThat(bars.get(0).select(".remaining").text()).isEqualTo("$12.75 over");
        assertThat(bars.get(1).select(".pill[data-level=WARNING_EXCEEDED]").text()).isEqualTo("Near limit");
        assertThat(bars.get(1).select(".remaining").text()).isEqualTo("$15.00 left");
        assertThat(bars.get(2).select(".pill[data-level=OK]").text()).isEqualTo("On track");
        assertThat(bars.get(2).select(".remaining").text()).isEqualTo("$190.00 left");
    }

    @Test
    void moneyShowsWithDollarSignButMachineValuesStayRaw() throws Exception {
        budgets.upsert("food", new BigDecimal("1234.00"), 80);
        spend("Market", "food", "1000.50", MARCH_2);

        Document page = dashboard();

        assertThat(page.selectFirst("#budget-bars .figure").text()).isEqualTo("$1,000.50 of $1,234.00");
        assertThat(page.selectFirst("progress").attr("value")).isEqualTo("1000.50");
        assertThat(page.selectFirst("progress").attr("max")).isEqualTo("1234.00");
        assertThat(page.selectFirst("#recent-expenses .amount").text()).isEqualTo("$1,000.50");
    }

    @Test
    void budgetBarsWorstFirst() throws Exception {
        budgets.upsert("food", new BigDecimal("100.00"), 80);
        budgets.upsert("fun", new BigDecimal("50.00"), 80);
        budgets.upsert("home", new BigDecimal("200.00"), 80);
        budgets.upsert("bills", new BigDecimal("100.00"), 80);
        spend("Market", "food", "85.00", MARCH_2);
        spend("Cinema", "fun", "62.75", MARCH_2);
        spend("Hardware", "home", "10.00", MARCH_2);
        spend("Power", "bills", "50.00", MARCH_2);

        assertThat(dashboard().select("#budget-bars .budget-bar strong").eachText())
                .containsExactly("fun", "food", "bills", "home");
    }

    @Test
    void emptyStateLinksToQuickAdd() throws Exception {
        Document page = dashboard();

        assertThat(page.select("#empty-state a[href=/add]")).hasSize(1);
        assertThat(page.select("#recent-expenses")).isEmpty();
        assertThat(page.select("progress")).isEmpty();
    }

    private static final LocalDateTime MARCH_2 = LocalDateTime.of(2026, 3, 2, 9, 0);

    @Test
    void memberFilterScopesTotalsAndRecent() throws Exception {
        budgets.upsert("food", new BigDecimal("100.00"), 80);
        spendBy(mom, "Market", "Food", "10.00", MARCH_2);
        spendBy(kid, "Candy", "Food", "30.00", MARCH_2);
        spendBy(null, "Legacy", "Food", "5.00", MARCH_2);

        Document kidOnly = dashboard("/?member=" + kid);

        assertThat(kidOnly.selectFirst("progress").attr("value")).isEqualTo("30.00");
        // The limit stays the household's.
        assertThat(kidOnly.selectFirst("progress").attr("max")).isEqualTo("100.00");
        assertThat(kidOnly.select("#recent-expenses li.expense")).hasSize(1);
        assertThat(kidOnly.select("#recent-expenses li.expense").text()).contains("Candy");
        assertThat(kidOnly.select("#member-filter a").eachText()).containsExactly("Everyone", "admin", "Mom", "Kid");
        assertThat(kidOnly.select("#member-filter a[aria-current]").eachText()).containsExactly("Kid");

        Document everyone = dashboard("/");
        assertThat(everyone.selectFirst("progress").attr("value")).isEqualTo("45.00");
        assertThat(everyone.select("#recent-expenses li.expense")).hasSize(3);
        assertThat(everyone.select("#member-filter a[aria-current]").eachText()).containsExactly("Everyone");
        // Works without JavaScript: plain links.
        assertThat(everyone.select("#member-filter a").eachAttr("href")).contains("/?member=" + kid, "/");
    }

    @ParameterizedTest(name = "member={0}")
    @ValueSource(strings = {"abc", "999999", ""})
    void unknownMemberMeansEveryone(String member) throws Exception {
        spendBy(mom, "Market", "Food", "10.00", MARCH_2);
        spendBy(kid, "Candy", "Food", "30.00", MARCH_2);

        Document page = dashboard("/?member=" + member);

        assertThat(page.select("#recent-expenses li.expense")).hasSize(2);
        assertThat(page.select("#member-filter a[aria-current]").eachText()).containsExactly("Everyone");
    }

    @Test
    void showsWhoSpent() throws Exception {
        spendBy(kid, "Candy", "Food", "3.00", MARCH_2);
        spendBy(null, "Legacy", "Food", "5.00", MARCH_2.plusHours(1));

        var rows = dashboard("/").select("#recent-expenses li.expense");

        // Newest first: the unattributed one shows no name, the other names its spender on the meta line.
        assertThat(rows.get(0).select(".spender")).isEmpty();
        assertThat(rows.get(1).select(".meta .spender").text()).isEqualTo("Kid");
    }

    @Test
    void editLinkOnlyWhereTheViewerMayEdit() throws Exception {
        ExpenseEntity mine = expenses.save(new ExpenseEntity(null, "mine", "Market", new BigDecimal("1.00"), "Food",
                MARCH_2, mom, mom));
        ExpenseEntity kids = expenses.save(new ExpenseEntity(null, "kids", "Candy", new BigDecimal("2.00"), "Food",
                MARCH_2.plusHours(1), kid, kid));
        ExpenseEntity legacy = expenses.save(new ExpenseEntity(null, "old", "Old", new BigDecimal("3.00"), "Food",
                MARCH_2.plusHours(2), null, null));

        // Mom: only the one she created. The link sits on the row's meta line.
        Document asMom = dashboard("/");
        assertThat(asMom.select("#recent-expenses a[href$=/edit]").eachAttr("href"))
                .containsExactly("/expenses/" + mine.id() + "/edit");

        // Admin: every row, including the one with no creator.
        Document asAdmin = Html.of(mockMvc.perform(get("/").with(user("admin").roles("ADMIN")))
                .andExpect(status().isOk()));
        assertThat(asAdmin.select("#recent-expenses a[href$=/edit]").eachAttr("href"))
                .containsExactlyInAnyOrder("/expenses/" + mine.id() + "/edit", "/expenses/" + kids.id() + "/edit",
                        "/expenses/" + legacy.id() + "/edit");
    }

    @Test
    void afterAnEditOrDeleteTheDashboardSaysSo() throws Exception {
        assertThat(dashboard("/?edited").select("[role=status]").text()).contains("updated");
        assertThat(dashboard("/?deleted").select("[role=status]").text()).contains("deleted");
        assertThat(dashboard("/").select("[role=status]")).isEmpty();
    }

    @Test
    void anonymousRedirectsToLogin() throws Exception {
        // A browser always asks for HTML; without it Spring Security answers 403 instead of redirecting.
        mockMvc.perform(get("/").accept(MediaType.TEXT_HTML)).andExpect(status().isFound()).andExpect(redirectedUrl("/login"));
    }
}
