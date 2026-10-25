package com.centry.web;

import com.centry.model.User;
import com.centry.repository.BudgetStatusQuery;
import com.centry.repository.ExpenseRepository;
import com.centry.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.time.Clock;
import java.time.YearMonth;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Home page: this month's budgets and the latest expenses, for everyone or for one household member. */
@Controller
@RequiredArgsConstructor
class DashboardPageController {

    static final int RECENT_LIMIT = 20;

    private final BudgetStatusQuery statusQuery;
    private final ExpenseRepository expenses;
    private final UserRepository users;
    private final Clock clock;

    /**
     * @param member a user id to look at one member's spending; anything that is not a known user (garbage, an
     *               id that does not exist, empty) shows everyone rather than an error
     */
    @GetMapping("/")
    String dashboard(@RequestParam(required = false) String member, Model model) {
        YearMonth month = YearMonth.now(clock);
        List<User> members = users.findAll();
        Long selected = members.stream().map(User::id).filter(id -> id.toString().equals(member)).findFirst()
                .orElse(null);
        Map<Long, String> names = new HashMap<>();
        members.forEach(user -> names.put(user.id(), user.displayName()));

        model.addAttribute("month", month);
        model.addAttribute("members", members);
        model.addAttribute("selected", selected);
        model.addAttribute("budgets", statusQuery.all(month, selected));
        model.addAttribute("recent", expenses.findRecent(RECENT_LIMIT, selected).stream()
                .map(expense -> new RecentExpense(expense, names.get(expense.spentBy()))).toList());
        return "dashboard";
    }
}
