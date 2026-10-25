package com.centry.web;

import com.centry.entity.ExpenseEntity;
import com.centry.repository.BudgetRepository;
import com.centry.repository.ExpenseRepository;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
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

    @BeforeEach
    void clean() {
        expenses.deleteAll();
        jdbc.sql("DELETE FROM budget").update();
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

        var rows = dashboard().select("#recent-expenses tbody tr");

        assertThat(rows).hasSize(20);
        assertThat(rows.get(0).text()).contains("2026-03-25").contains("shop-25").contains("misc").contains("25.00");
        assertThat(rows.get(19).text()).contains("shop-06");
    }

    @Test
    void emptyStateLinksToQuickAdd() throws Exception {
        Document page = dashboard();

        assertThat(page.select("#empty-state a[href=/add]")).hasSize(1);
        assertThat(page.select("#recent-expenses")).isEmpty();
        assertThat(page.select("progress")).isEmpty();
    }

    @Test
    void anonymousRedirectsToLogin() throws Exception {
        // A browser always asks for HTML; without it Spring Security answers 403 instead of redirecting.
        mockMvc.perform(get("/").accept(MediaType.TEXT_HTML)).andExpect(status().isFound()).andExpect(redirectedUrl("/login"));
    }
}
