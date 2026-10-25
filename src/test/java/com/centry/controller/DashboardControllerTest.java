package com.centry.controller;

import org.springframework.security.test.context.support.WithMockUser;
import com.centry.entity.BudgetAlertEntity;
import com.centry.entity.ExpenseEntity;
import com.centry.entity.SubscriptionAlertEntity;
import com.centry.model.BudgetLevel;
import com.centry.repository.BudgetAlertRepository;
import com.centry.repository.ExpenseRepository;
import com.centry.repository.SubscriptionAlertRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(DashboardController.class)
@WithMockUser
class DashboardControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ExpenseRepository expenseRepository;

    @MockitoBean
    private BudgetAlertRepository budgetAlertRepository;

    @MockitoBean
    private SubscriptionAlertRepository subscriptionAlertRepository;

    @Test
    void summaryAggregatesCategorySpendAndCount() throws Exception {
        when(expenseRepository.sumAmountByCategory()).thenReturn(Map.of(
                "dining", new BigDecimal("42.50"),
                "travel", new BigDecimal("300.00")));
        when(expenseRepository.count()).thenReturn(7L);

        mockMvc.perform(get("/api/v1/dashboard/summary"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.categorySpend.dining").value(42.50))
                .andExpect(jsonPath("$.categorySpend.travel").value(300.00))
                .andExpect(jsonPath("$.categorySpend.length()").value(2))
                .andExpect(jsonPath("$.totalExpensesCount").value(7));
    }

    @Test
    void summaryWithNoExpensesReturnsEmptyMapAndZeroCount() throws Exception {
        when(expenseRepository.sumAmountByCategory()).thenReturn(Collections.emptyMap());
        when(expenseRepository.count()).thenReturn(0L);

        mockMvc.perform(get("/api/v1/dashboard/summary"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.categorySpend").isMap())
                .andExpect(jsonPath("$.categorySpend").isEmpty())
                .andExpect(jsonPath("$.totalExpensesCount").value(0));
    }

    @Test
    void expensesReturnsAllExpenses() throws Exception {
        when(expenseRepository.findAll()).thenReturn(List.of(
                new ExpenseEntity(1L, "tx-1", "Cafe", new BigDecimal("4.50"), "dining",
                        LocalDateTime.of(2026, 1, 2, 3, 4, 5))));

        mockMvc.perform(get("/api/v1/dashboard/expenses"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].id").value(1))
                .andExpect(jsonPath("$[0].transactionId").value("tx-1"))
                .andExpect(jsonPath("$[0].merchant").value("Cafe"))
                .andExpect(jsonPath("$[0].amount").value(4.50))
                .andExpect(jsonPath("$[0].category").value("dining"))
                .andExpect(jsonPath("$[0].timestamp").value("2026-01-02T03:04:05"));
    }

    @Test
    void budgetAlertsReturnsRepositoryResults() throws Exception {
        when(budgetAlertRepository.findTop50ByOrderByTimestampDesc()).thenReturn(List.of(
                new BudgetAlertEntity(2L, "dining", new BigDecimal("105.00"), new BigDecimal("100.00"),
                        new BigDecimal("80.00"), BudgetLevel.LIMIT_EXCEEDED, "over", null)));

        mockMvc.perform(get("/api/v1/dashboard/alerts/budget"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].category").value("dining"))
                .andExpect(jsonPath("$[0].currentSpend").value(105.00))
                .andExpect(jsonPath("$[0].status").value("LIMIT_EXCEEDED"))
                .andExpect(jsonPath("$[0].message").value("over"));
    }

    @Test
    void subscriptionAlertsReturnsRepositoryResults() throws Exception {
        when(subscriptionAlertRepository.findTop50ByOrderByTimestampDesc()).thenReturn(List.of(
                new SubscriptionAlertEntity(3L, "Netflix", new BigDecimal("15.49"), new BigDecimal("17.99"),
                        "PRICE_INCREASE", "up", null)));

        mockMvc.perform(get("/api/v1/dashboard/alerts/subscriptions"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].merchant").value("Netflix"))
                .andExpect(jsonPath("$[0].previousAmount").value(15.49))
                .andExpect(jsonPath("$[0].currentAmount").value(17.99))
                .andExpect(jsonPath("$[0].anomalyType").value("PRICE_INCREASE"));
    }
}
