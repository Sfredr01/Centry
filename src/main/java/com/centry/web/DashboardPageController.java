package com.centry.web;

import com.centry.model.User;
import com.centry.repository.BudgetStatusQuery;
import com.centry.repository.ExpenseRepository;
import com.centry.repository.UserRepository;
import com.centry.security.Actor;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
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
     * @param params {@code member} is a user id to look at one member's spending; anything that is not a known
     *               user (garbage, an id that does not exist, empty) shows everyone rather than an error.
     *               {@code edited} and {@code deleted} are bare post/redirect/get flags.
     */
    @GetMapping("/")
    String dashboard(@RequestParam Map<String, String> params, Authentication viewer, Model model) {
        YearMonth month = YearMonth.now(clock);
        String member = params.get("member");
        Actor actor = Actor.of(viewer, users);
        List<User> members = users.findAll();
        Long selected = members.stream().map(User::id).filter(id -> id.toString().equals(member)).findFirst()
                .orElse(null);
        Map<Long, String> names = new HashMap<>();
        members.forEach(user -> names.put(user.id(), user.displayName()));

        if (params.containsKey("edited")) {
            model.addAttribute("message", "Expense updated.");
        } else if (params.containsKey("deleted")) {
            model.addAttribute("message", "Expense deleted.");
        }
        model.addAttribute("month", month);
        model.addAttribute("members", members);
        model.addAttribute("selected", selected);
        model.addAttribute("budgets", statusQuery.all(month, selected));
        model.addAttribute("recent", expenses.findRecent(RECENT_LIMIT, selected).stream()
                .map(expense -> new RecentExpense(expense, names.get(expense.spentBy()), actor.canModify(expense)))
                .toList());
        return "dashboard";
    }
}
