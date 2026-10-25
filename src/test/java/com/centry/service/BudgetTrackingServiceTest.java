package com.centry.service;

import com.centry.config.BudgetConfigProperties;
import com.centry.model.BudgetStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class BudgetTrackingServiceTest {

    private BudgetTrackingService budgetTrackingService;

    @BeforeEach
    void setUp() {
        BudgetConfigProperties properties = new BudgetConfigProperties();
        Map<String, BudgetConfigProperties.CategoryLimit> categories = new HashMap<>();
        
        BudgetConfigProperties.CategoryLimit limit = new BudgetConfigProperties.CategoryLimit();
        limit.setMonthlyLimit(BigDecimal.valueOf(100.00));
        limit.setWarningThresholdPercent(0.80); // warning at 80.00
        
        categories.put("dining", limit);
        properties.setCategories(categories);

        budgetTrackingService = new BudgetTrackingService(properties);
    }

    @Test
    void testRecordExpenseOk() {
        BudgetStatus status = budgetTrackingService.recordExpense("dining", BigDecimal.valueOf(50.00));
        assertNotNull(status);
        assertEquals(BudgetStatus.Status.OK, status.getStatus());
        assertEquals(BigDecimal.valueOf(50.00), status.getCurrentSpend());
    }

    @Test
    void testRecordExpenseWarningAndLimit() {
        // First push to warning (>= 80.00)
        BudgetStatus status1 = budgetTrackingService.recordExpense("dining", BigDecimal.valueOf(85.00));
        assertEquals(BudgetStatus.Status.WARNING_EXCEEDED, status1.getStatus());

        // Push past limit (>= 100.00)
        BudgetStatus status2 = budgetTrackingService.recordExpense("dining", BigDecimal.valueOf(20.00)); // total 105.00
        assertEquals(BudgetStatus.Status.LIMIT_EXCEEDED, status2.getStatus());
    }
}
