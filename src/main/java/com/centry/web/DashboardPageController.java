package com.centry.web;

import com.centry.repository.BudgetStatusQuery;
import com.centry.repository.ExpenseRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

import java.time.Clock;
import java.time.YearMonth;

/** Home page: this month's budgets and the latest expenses. */
@Controller
@RequiredArgsConstructor
class DashboardPageController {

    static final int RECENT_LIMIT = 20;

    private final BudgetStatusQuery statusQuery;
    private final ExpenseRepository expenses;
    private final Clock clock;

    @GetMapping("/")
    String dashboard(Model model) {
        YearMonth month = YearMonth.now(clock);
        model.addAttribute("month", month);
        model.addAttribute("budgets", statusQuery.all(month));
        model.addAttribute("recent", expenses.findRecent(RECENT_LIMIT, null));
        return "dashboard";
    }
}
