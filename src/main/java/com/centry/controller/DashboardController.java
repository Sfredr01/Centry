package com.centry.controller;

import com.centry.entity.BudgetAlertEntity;
import com.centry.entity.ExpenseEntity;
import com.centry.entity.SubscriptionAlertEntity;
import com.centry.repository.BudgetAlertRepository;
import com.centry.repository.ExpenseRepository;
import com.centry.repository.SubscriptionAlertRepository;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/dashboard")
public class DashboardController {

    private final ExpenseRepository expenseRepository;
    private final BudgetAlertRepository budgetAlertRepository;
    private final SubscriptionAlertRepository subscriptionAlertRepository;

    public DashboardController(ExpenseRepository expenseRepository,
                               BudgetAlertRepository budgetAlertRepository,
                               SubscriptionAlertRepository subscriptionAlertRepository) {
        this.expenseRepository = expenseRepository;
        this.budgetAlertRepository = budgetAlertRepository;
        this.subscriptionAlertRepository = subscriptionAlertRepository;
    }

    @GetMapping("/summary")
    public ResponseEntity<Map<String, Object>> getSummary() {
        Map<String, BigDecimal> categorySpend = new HashMap<>();
        List<Object[]> sums = expenseRepository.sumAmountByCategory();
        for (Object[] row : sums) {
            String category = (String) row[0];
            BigDecimal total = (BigDecimal) row[1];
            categorySpend.put(category, total);
        }

        Map<String, Object> response = new HashMap<>();
        response.put("categorySpend", categorySpend);
        response.put("totalExpensesCount", expenseRepository.count());
        return ResponseEntity.ok(response);
    }

    @GetMapping("/expenses")
    public ResponseEntity<List<ExpenseEntity>> getRecentExpenses() {
        return ResponseEntity.ok(expenseRepository.findAll());
    }

    @GetMapping("/alerts/budget")
    public ResponseEntity<List<BudgetAlertEntity>> getBudgetAlerts() {
        return ResponseEntity.ok(budgetAlertRepository.findTop50ByOrderByTimestampDesc());
    }

    @GetMapping("/alerts/subscriptions")
    public ResponseEntity<List<SubscriptionAlertEntity>> getSubscriptionAlerts() {
        return ResponseEntity.ok(subscriptionAlertRepository.findTop50ByOrderByTimestampDesc());
    }
}
