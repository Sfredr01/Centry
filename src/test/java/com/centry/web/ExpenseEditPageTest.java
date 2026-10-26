package com.centry.web;

import com.centry.entity.ExpenseEntity;
import com.centry.model.Role;
import com.centry.repository.BudgetRepository;
import com.centry.repository.ExpenseRepository;
import com.centry.repository.UserRepository;
import org.jsoup.nodes.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// "Now" is 15 March 2026, 10:00 UTC (FixedClockConfig).
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(FixedClockConfig.class)
class ExpenseEditPageTest {

    private static final LocalDateTime MARCH_2 = LocalDateTime.of(2026, 3, 2, 9, 30);

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ExpenseRepository expenses;

    @Autowired
    private BudgetRepository budgets;

    @Autowired
    private UserRepository users;

    @Autowired
    private JdbcClient jdbc;

    private long mom;
    private long dad;
    private long kid;
    /** Created by mom, spent by mom. */
    private ExpenseEntity momsExpense;

    @BeforeEach
    void clean() {
        expenses.deleteAll();
        jdbc.sql("DELETE FROM budget").update();
        jdbc.sql("DELETE FROM budget_alert").update();
        jdbc.sql("DELETE FROM users WHERE username <> 'admin'").update();
        LocalDateTime joined = LocalDateTime.of(2026, 1, 1, 0, 0);
        mom = users.insert("mom", "Mom", null, Role.MEMBER, joined).id();
        dad = users.insert("dad", "Dad", null, Role.MEMBER, joined).id();
        kid = users.insert("kid", "Kid", null, Role.MEMBER, joined).id();
        momsExpense = expenses.save(new ExpenseEntity(null, "tx-mom", "Market", new BigDecimal("12.50"), "Food",
                MARCH_2, mom, mom));
    }

    private static org.springframework.test.web.servlet.request.RequestPostProcessor asMom() {
        return user("mom").roles("MEMBER");
    }

    private static org.springframework.test.web.servlet.request.RequestPostProcessor asDad() {
        return user("dad").roles("MEMBER");
    }

    private static org.springframework.test.web.servlet.request.RequestPostProcessor asAdmin() {
        return user("admin").roles("ADMIN");
    }

    private ResultActions getEdit(long id, org.springframework.test.web.servlet.request.RequestPostProcessor who)
            throws Exception {
        return mockMvc.perform(get("/expenses/" + id + "/edit").with(who));
    }

    /** A valid edit of {@code id}; tests override single fields. */
    private MockHttpServletRequestBuilder save(long id, org.springframework.test.web.servlet.request.RequestPostProcessor who,
            String amount, String category, String merchant, String date, String spentBy) {
        return post("/expenses/" + id).with(who).with(csrf()).param("amount", amount).param("category", category)
                .param("merchant", merchant).param("date", date).param("spentBy", spentBy);
    }

    private MockHttpServletRequestBuilder delete(long id, org.springframework.test.web.servlet.request.RequestPostProcessor who) {
        return post("/expenses/" + id + "/delete").with(who).with(csrf());
    }

    private ExpenseEntity reload() {
        return expenses.findById(momsExpense.id()).orElseThrow();
    }

    private ExpenseEntity legacyExpense() {
        return expenses.save(new ExpenseEntity(null, "tx-legacy", "Old", new BigDecimal("1.00"), "Food", MARCH_2,
                null, null));
    }

    @Test
    void creatorEdits() throws Exception {
        Document form = Html.of(getEdit(momsExpense.id(), asMom()).andExpect(status().isOk()));

        assertThat(form.select("form[method=post][action=/expenses/" + momsExpense.id() + "]")).hasSize(1);
        assertThat(form.selectFirst("input[name=amount]").attr("value")).isEqualTo("12.50");
        assertThat(form.selectFirst("input[name=category]").attr("value")).isEqualTo("Food");
        assertThat(form.selectFirst("input[name=merchant]").attr("value")).isEqualTo("Market");
        assertThat(form.selectFirst("input[name=date]").attr("value")).isEqualTo("2026-03-02");
        assertThat(form.selectFirst("select[name=spentBy] option[selected]").attr("value"))
                .isEqualTo(String.valueOf(mom));
        assertThat(form.select("form[action=/expenses/" + momsExpense.id() + "/delete]")).hasSize(1);

        mockMvc.perform(save(momsExpense.id(), asMom(), "20.00", "Fun", "Cinema", "2026-03-10", String.valueOf(kid)))
                .andExpect(status().isFound())
                .andExpect(redirectedUrl("/?edited"));

        // Same row and transaction id; only the editable fields moved, and the creator is still mom.
        assertThat(reload()).isEqualTo(new ExpenseEntity(momsExpense.id(), "tx-mom", "Cinema", new BigDecimal("20.00"),
                "Fun", LocalDateTime.of(2026, 3, 10, 0, 0), kid, mom));
    }

    @Test
    void unchangedDateKeepsTheTimeOfDay() throws Exception {
        mockMvc.perform(save(momsExpense.id(), asMom(), "13.00", "Food", "Market", "2026-03-02", String.valueOf(mom)))
                .andExpect(status().isFound());

        assertThat(reload().timestamp()).isEqualTo(MARCH_2);
        assertThat(reload().amount()).isEqualByComparingTo("13.00");
    }

    @Test
    void movingTheDateToTodayUsesTheClock() throws Exception {
        mockMvc.perform(save(momsExpense.id(), asMom(), "12.50", "Food", "Market", "2026-03-15", String.valueOf(mom)))
                .andExpect(status().isFound());

        assertThat(reload().timestamp()).isEqualTo(LocalDateTime.of(2026, 3, 15, 10, 0));
    }

    @Test
    void adminEditsAnyone() throws Exception {
        mockMvc.perform(save(momsExpense.id(), asAdmin(), "99.00", "Food", "Market", "2026-03-02", String.valueOf(mom)))
                .andExpect(status().isFound());

        assertThat(reload().amount()).isEqualByComparingTo("99.00");
        assertThat(reload().createdBy()).isEqualTo(mom);
    }

    @Test
    void otherMember403() throws Exception {
        getEdit(momsExpense.id(), asDad()).andExpect(status().isForbidden());
        mockMvc.perform(save(momsExpense.id(), asDad(), "1.00", "Food", "Market", "2026-03-02", String.valueOf(dad)))
                .andExpect(status().isForbidden());
        mockMvc.perform(delete(momsExpense.id(), asDad())).andExpect(status().isForbidden());

        assertThat(reload()).isEqualTo(momsExpense);
    }

    // A login name that is not a stored user has no id, so it can never be anyone's creator.
    @Test
    void loginWithoutAUserRowCannotEditAnything() throws Exception {
        getEdit(momsExpense.id(), user("ghost").roles("MEMBER")).andExpect(status().isForbidden());
        getEdit(legacyExpense().id(), user("ghost").roles("MEMBER")).andExpect(status().isForbidden());
    }

    @Test
    void legacyRowAdminOnly() throws Exception {
        ExpenseEntity legacy = legacyExpense();

        getEdit(legacy.id(), asMom()).andExpect(status().isForbidden());
        mockMvc.perform(delete(legacy.id(), asMom())).andExpect(status().isForbidden());
        assertThat(expenses.findById(legacy.id())).isPresent();

        Document form = Html.of(getEdit(legacy.id(), asAdmin()).andExpect(status().isOk()));
        // An unattributed row stays unattributed unless someone picks a spender.
        assertThat(form.selectFirst("select[name=spentBy] option[selected]").attr("value")).isEmpty();
        mockMvc.perform(save(legacy.id(), asAdmin(), "2.00", "Food", "Old", "2026-03-02", ""))
                .andExpect(status().isFound());
        assertThat(expenses.findById(legacy.id()).orElseThrow().spentBy()).isNull();
        assertThat(expenses.findById(legacy.id()).orElseThrow().amount()).isEqualByComparingTo("2.00");
    }

    @Test
    void missing404() throws Exception {
        getEdit(999_999L, asAdmin()).andExpect(status().isNotFound());
        mockMvc.perform(save(999_999L, asAdmin(), "1.00", "Food", "x", "2026-03-02", "")).andExpect(status().isNotFound());
        mockMvc.perform(delete(999_999L, asAdmin())).andExpect(status().isNotFound());
    }

    @Test
    void deleteRemovesAndTotalsDrop() throws Exception {
        budgets.upsert("food", new BigDecimal("100.00"), 80);
        expenses.save(new ExpenseEntity(null, "tx-other", "Shop", new BigDecimal("30.00"), "Food", MARCH_2, dad, dad));
        assertThat(dashboardSpent()).isEqualTo("42.50");

        mockMvc.perform(delete(momsExpense.id(), asMom()))
                .andExpect(status().isFound())
                .andExpect(redirectedUrl("/?deleted"));

        assertThat(expenses.findById(momsExpense.id())).isEmpty();
        assertThat(dashboardSpent()).isEqualTo("30.00");
    }

    private String dashboardSpent() throws Exception {
        return Html.of(mockMvc.perform(get("/").with(asMom())).andExpect(status().isOk()))
                .selectFirst("progress").attr("value");
    }

    private long alertRows() {
        return jdbc.sql("SELECT count(*) FROM budget_alert").query(Long.class).single();
    }

    @Test
    void editCrossingThresholdAlertsOnce() throws Exception {
        budgets.upsert("food", new BigDecimal("100.00"), 80);
        assertThat(alertRows()).isZero();

        mockMvc.perform(save(momsExpense.id(), asMom(), "85.00", "Food", "Market", "2026-03-02", String.valueOf(mom)))
                .andExpect(status().isFound());
        assertThat(alertRows()).isEqualTo(1);

        // Still over the warning line: no second alert.
        mockMvc.perform(save(momsExpense.id(), asMom(), "86.00", "Food", "Market", "2026-03-02", String.valueOf(mom)))
                .andExpect(status().isFound());
        assertThat(alertRows()).isEqualTo(1);

        // Dropping back under never takes the sent alert away, and going over again does not repeat it.
        mockMvc.perform(save(momsExpense.id(), asMom(), "10.00", "Food", "Market", "2026-03-02", String.valueOf(mom)))
                .andExpect(status().isFound());
        mockMvc.perform(save(momsExpense.id(), asMom(), "90.00", "Food", "Market", "2026-03-02", String.valueOf(mom)))
                .andExpect(status().isFound());
        assertThat(alertRows()).isEqualTo(1);
    }

    @Test
    void editingIntoABudgetedCategoryAlertsThatCategory() throws Exception {
        budgets.upsert("fun", new BigDecimal("50.00"), 80);

        mockMvc.perform(save(momsExpense.id(), asMom(), "45.00", "Fun", "Cinema", "2026-03-02", String.valueOf(mom)))
                .andExpect(status().isFound());

        assertThat(jdbc.sql("SELECT category FROM budget_alert").query(String.class).single()).isEqualTo("fun");
    }

    @Test
    void invalidEditRerenders422() throws Exception {
        Document page = Html.of(mockMvc.perform(save(momsExpense.id(), asMom(), "abc", "Food", "Market", "2026-03-02",
                String.valueOf(mom))).andExpect(status().isUnprocessableContent()));

        assertThat(page.select("nav.tabbar")).hasSize(1);
        assertThat(page.select("#amount-error").text()).isNotBlank();
        assertThat(page.selectFirst("input[name=amount]").attr("aria-invalid")).isEqualTo("true");
        // What was typed survives and the form still posts to the same expense.
        assertThat(page.selectFirst("input[name=amount]").attr("value")).isEqualTo("abc");
        assertThat(page.select("form[action=/expenses/" + momsExpense.id() + "]")).hasSize(1);
        assertThat(reload()).isEqualTo(momsExpense);
    }

    @Test
    void unknownSpenderRerenders422() throws Exception {
        Document page = Html.of(mockMvc.perform(save(momsExpense.id(), asMom(), "5.00", "Food", "Market", "2026-03-02",
                "999999")).andExpect(status().isUnprocessableContent()));

        assertThat(page.select("#spentBy-error").text()).isNotBlank();
        assertThat(reload()).isEqualTo(momsExpense);
    }

    @Test
    void postsNeedCsrf() throws Exception {
        mockMvc.perform(post("/expenses/" + momsExpense.id()).with(asMom()).param("amount", "1.00"))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/expenses/" + momsExpense.id() + "/delete").with(asMom()))
                .andExpect(status().isForbidden());

        assertThat(reload()).isEqualTo(momsExpense);
    }
}
