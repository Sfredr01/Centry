package com.centry.web;

import com.centry.entity.ExpenseEntity;
import com.centry.model.BudgetStatus;
import com.centry.repository.BudgetRepository;
import com.centry.repository.BudgetStatusQuery;
import com.centry.repository.ExpenseRepository;
import org.jsoup.nodes.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// "Now" is March 2026 (FixedClockConfig).
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(FixedClockConfig.class)
class BudgetsPageTest {

    private static final YearMonth MARCH = YearMonth.of(2026, 3);

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private BudgetRepository budgets;

    @Autowired
    private BudgetStatusQuery statuses;

    @Autowired
    private ExpenseRepository expenses;

    @Autowired
    private JdbcClient jdbc;

    @BeforeEach
    void seed() {
        expenses.deleteAll();
        jdbc.sql("DELETE FROM budget").update();
        budgets.upsert("food", new BigDecimal("100.00"), 80);
        expenses.save(new ExpenseEntity(null, UUID.randomUUID().toString(), "Market", new BigDecimal("85.00"),
                "Food", LocalDateTime.of(2026, 3, 2, 9, 0), null, null));
    }

    private static RequestPostProcessor admin() {
        return user("admin").roles("ADMIN");
    }

    private static RequestPostProcessor mom() {
        return user("mom").roles("MEMBER");
    }

    private MockHttpServletRequestBuilder form(String url, RequestPostProcessor who, String... params) {
        MockHttpServletRequestBuilder request = post(url).with(who).with(csrf());
        for (int i = 0; i < params.length; i += 2) {
            request.param(params[i], params[i + 1]);
        }
        return request;
    }

    private BudgetStatus food() {
        return statuses.status("food", MARCH).orElseThrow();
    }

    @Test
    void adminSeesEditFormsAndCanUpsert() throws Exception {
        Document page = Html.of(mockMvc.perform(get("/budgets").with(admin())).andExpect(status().isOk()));

        // One card per budget, not a table: a five-column table with inputs cannot fit a phone screen.
        assertThat(page.select("#budgets table")).isEmpty();
        var card = page.selectFirst("#budgets article");
        assertThat(card.text()).contains("food").contains("$85.00");
        var save = card.selectFirst("form[method=post][action=/budgets/food]");
        assertThat(save.select("input[name=monthlyLimit]").attr("value")).isEqualTo("100.00");
        assertThat(save.select("input[name=warnPercent]").attr("value")).isEqualTo("80");
        assertThat(page.select("form[method=post][action=/budgets/food/delete]")).hasSize(1);
        assertThat(page.select("form[method=post][action=/budgets] input[name=category]")).hasSize(1);
        assertThat(page.select("form[action=/budgets/food] input[name=_csrf]")).hasSize(1);

        mockMvc.perform(form("/budgets/Food", admin(), "monthlyLimit", "150.00", "warnPercent", "70"))
                .andExpect(status().isFound()).andExpect(redirectedUrl("/budgets"));
        assertThat(food().monthlyLimit()).isEqualByComparingTo("150.00");
        assertThat(food().warnPercent()).isEqualTo(70);

        // The add form creates a new budget; no warn percent means the API default of 80.
        mockMvc.perform(form("/budgets", admin(), "category", " Travel ", "monthlyLimit", "500", "warnPercent", ""))
                .andExpect(status().isFound()).andExpect(redirectedUrl("/budgets"));
        assertThat(budgets.categoryKeys()).containsExactly("food", "travel");
        assertThat(statuses.status("travel", MARCH).orElseThrow().warnPercent()).isEqualTo(80);
    }

    @Test
    void cardsWorstFirst() throws Exception {
        budgets.upsert("bills", new BigDecimal("100.00"), 80);
        budgets.upsert("fun", new BigDecimal("10.00"), 80);
        expenses.save(new ExpenseEntity(null, UUID.randomUUID().toString(), "Cinema", new BigDecimal("20.00"),
                "fun", LocalDateTime.of(2026, 3, 3, 20, 0), null, null));

        Document page = Html.of(mockMvc.perform(get("/budgets").with(mom())).andExpect(status().isOk()));

        assertThat(page.select("#budgets article > header strong").eachText())
                .containsExactly("fun", "food", "bills");
    }

    @Test
    void adminCanDelete() throws Exception {
        mockMvc.perform(form("/budgets/food/delete", admin()))
                .andExpect(status().isFound()).andExpect(redirectedUrl("/budgets"));

        assertThat(budgets.categoryKeys()).isEmpty();
        // Deleting what is already gone is not an error.
        mockMvc.perform(form("/budgets/food/delete", admin())).andExpect(redirectedUrl("/budgets"));
    }

    @Test
    void memberSeesReadOnlyTable() throws Exception {
        Document page = Html.of(mockMvc.perform(get("/budgets").with(mom())).andExpect(status().isOk()));

        assertThat(page.select("#budgets table")).isEmpty();
        assertThat(page.selectFirst("#budgets article").text()).contains("food").contains("$85.00")
                .contains("$100.00").contains("80");
        assertThat(page.select("#budgets input")).isEmpty();
        // Only the logout form is left.
        assertThat(page.select("form").eachAttr("action")).containsExactly("/logout");
    }

    @Test
    void memberPost403() throws Exception {
        mockMvc.perform(form("/budgets/food", mom(), "monthlyLimit", "1.00", "warnPercent", "10"))
                .andExpect(status().isForbidden());
        mockMvc.perform(form("/budgets", mom(), "category", "gifts", "monthlyLimit", "1.00"))
                .andExpect(status().isForbidden());
        mockMvc.perform(form("/budgets/food/delete", mom())).andExpect(status().isForbidden());

        assertThat(food().monthlyLimit()).isEqualByComparingTo("100.00");
        assertThat(budgets.categoryKeys()).containsExactly("food");
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource(delimiter = '|', value = {
            "zero limit|food|0|80",
            "negative limit|food|-5.00|80",
            "sub-cent limit|food|10.001|80",
            "text limit|food|abc|80",
            "warn percent too high|food|10.00|100",
            "warn percent not a number|food|10.00|lots"})
    void invalidLimitRerendersWithError(String name, String category, String limit, String warn) throws Exception {
        Document page = Html.of(mockMvc.perform(form("/budgets/" + category, admin(),
                        "monthlyLimit", limit, "warnPercent", warn))
                .andExpect(status().isUnprocessableContent()));

        assertThat(page.select("[role=alert]").text()).isNotBlank();
        assertThat(page.select("#budgets article")).hasSize(1);
        assertThat(food().monthlyLimit()).isEqualByComparingTo("100.00");
        assertThat(food().warnPercent()).isEqualTo(80);
    }

    @Test
    void addWithoutCategoryIsRejected() throws Exception {
        Document page = Html.of(mockMvc.perform(form("/budgets", admin(), "category", "  ", "monthlyLimit", "5.00"))
                .andExpect(status().isUnprocessableContent()));

        assertThat(page.select("[role=alert]").text()).containsIgnoringCase("category");
        assertThat(budgets.categoryKeys()).isEqualTo(List.of("food"));
    }

    @Test
    void budgetsPageNeedsLogin() throws Exception {
        mockMvc.perform(get("/budgets").accept("text/html")).andExpect(status().isFound())
                .andExpect(redirectedUrl("/login"));
    }
}
