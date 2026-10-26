package com.centry.web;

import com.centry.model.Keys;
import com.centry.repository.BudgetRepository;
import com.centry.repository.ExpenseRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** The category suggestions both expense forms offer. */
@Component
@RequiredArgsConstructor
class CategoryChoices {

    private static final int RECENT_CATEGORIES = 20;

    private final ExpenseRepository expenses;
    private final BudgetRepository budgets;

    /** Budget categories first, then the ones used lately; one entry per normalized key. */
    List<String> known() {
        Map<String, String> byKey = new LinkedHashMap<>();
        budgets.categoryKeys().forEach(key -> byKey.putIfAbsent(key, key));
        expenses.recentCategories(RECENT_CATEGORIES).forEach(name -> byKey.putIfAbsent(Keys.of(name), name));
        return new ArrayList<>(byKey.values());
    }
}
