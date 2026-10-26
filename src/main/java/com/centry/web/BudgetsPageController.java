package com.centry.web;

import com.centry.controller.BudgetController;
import com.centry.dto.BudgetRequest;
import com.centry.model.Keys;
import com.centry.repository.BudgetRepository;
import com.centry.repository.BudgetStatusQuery;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.YearMonth;
import java.util.Iterator;

/**
 * Budgets page. Everyone signed in can read it; the forms are only rendered for admins, and the POST routes
 * are admin-only in {@code SecurityConfig}, so hiding the forms is a convenience and not the protection.
 * Plain forms with post/redirect/get: nothing here needs htmx.
 */
@Controller
@RequiredArgsConstructor
class BudgetsPageController {

    private static final String REDIRECT = "redirect:/budgets";

    private final BudgetRepository budgets;
    private final BudgetStatusQuery statuses;
    private final Validator validator;
    private final Clock clock;

    @GetMapping("/budgets")
    String list(Model model) {
        return render(model);
    }

    @PostMapping("/budgets")
    String add(@RequestParam String category, @RequestParam String monthlyLimit,
            @RequestParam(defaultValue = "") String warnPercent, Model model, HttpServletResponse response) {
        String key = Keys.of(category);
        if (key.isEmpty()) {
            return rejected("Category is required.", model, response);
        }
        return upsert(key, monthlyLimit, warnPercent, model, response);
    }

    @PostMapping("/budgets/{category}")
    String edit(@PathVariable String category, @RequestParam String monthlyLimit,
            @RequestParam(defaultValue = "") String warnPercent, Model model, HttpServletResponse response) {
        return upsert(Keys.of(category), monthlyLimit, warnPercent, model, response);
    }

    @PostMapping("/budgets/{category}/delete")
    String delete(@PathVariable String category) {
        // Idempotent: a budget that is already gone is the state the user asked for.
        budgets.delete(Keys.of(category));
        return REDIRECT;
    }

    private String upsert(String key, String limitText, String warnText, Model model, HttpServletResponse response) {
        BigDecimal limit;
        Integer warn;
        try {
            limit = new BigDecimal(limitText.trim());
            warn = warnText.isBlank() ? BudgetController.DEFAULT_WARN_PERCENT : Integer.valueOf(warnText.trim());
        } catch (NumberFormatException e) {
            return rejected("Monthly limit and warn percent must be numbers, like 100.00 and 80.", model, response);
        }
        // The API's own rules: positive limit with at most two decimals, warn percent 1-99.
        Iterator<ConstraintViolation<BudgetRequest>> problems =
                validator.validate(new BudgetRequest(limit, warn)).iterator();
        if (problems.hasNext()) {
            ConstraintViolation<BudgetRequest> problem = problems.next();
            return rejected(problem.getPropertyPath() + " " + problem.getMessage(), model, response);
        }
        budgets.upsert(key, limit, warn);
        return REDIRECT;
    }

    private String rejected(String error, Model model, HttpServletResponse response) {
        response.setStatus(422);
        model.addAttribute("error", error);
        return render(model);
    }

    private String render(Model model) {
        YearMonth month = YearMonth.now(clock);
        model.addAttribute("budgets", statuses.all(month));
        model.addAttribute("exportFrom", month.atDay(1));
        model.addAttribute("exportTo", month.atEndOfMonth());
        return "budgets";
    }
}
