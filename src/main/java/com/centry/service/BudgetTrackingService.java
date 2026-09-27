package com.centry.service;

import com.centry.config.BudgetConfigProperties;
import com.centry.config.BudgetConfigProperties.CategoryLimit;
import com.centry.model.BudgetStatus;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.YearMonth;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class BudgetTrackingService {

    private final BudgetConfigProperties budgetConfig;

    // Key format: "YYYY-MM:category" (e.g., "2026-09:dining")
    private final Map<String, BigDecimal> monthlyCategoryTotals = new ConcurrentHashMap<>();

    public BudgetTrackingService(BudgetConfigProperties budgetConfig) {
        this.budgetConfig = budgetConfig;
    }

    /**
     * Records a new transaction and returns the updated BudgetStatus.
     */
    public BudgetStatus recordExpense(String category, BigDecimal amount) {
        if (category == null || amount == null) {
            return null;
        }

        String normalizedCategory = category.trim().toLowerCase();
        String currentMonthKey = YearMonth.now().toString() + ":" + normalizedCategory;

        // Atomically update running total
        BigDecimal newTotal = monthlyCategoryTotals.merge(currentMonthKey, amount, BigDecimal::add);

        // Fetch configured limits for this category
        CategoryLimit limitConfig = budgetConfig.getCategories().get(normalizedCategory);

        if (limitConfig == null || limitConfig.getMonthlyLimit() == null) {
            return new BudgetStatus(category, newTotal, null, null, BudgetStatus.Status.OK);
        }

        BigDecimal limit = limitConfig.getMonthlyLimit();
        BigDecimal warningThreshold = limitConfig.getWarningAmount();

        BudgetStatus.Status status = BudgetStatus.Status.OK;
        if (newTotal.compareTo(limit) >= 0) {
            status = BudgetStatus.Status.LIMIT_EXCEEDED;
        } else if (newTotal.compareTo(warningThreshold) >= 0) {
            status = BudgetStatus.Status.WARNING_EXCEEDED;
        }

        return new BudgetStatus(category, newTotal, limit, warningThreshold, status);
    }

    public BigDecimal getCurrentTotal(String category) {
        String normalizedCategory = category.trim().toLowerCase();
        String currentMonthKey = YearMonth.now().toString() + ":" + normalizedCategory;
        return monthlyCategoryTotals.getOrDefault(currentMonthKey, BigDecimal.ZERO);
    }
}