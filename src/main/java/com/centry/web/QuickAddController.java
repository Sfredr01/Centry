package com.centry.web;

import com.centry.dto.ExpenseEvent;
import com.centry.entity.ExpenseEntity;
import com.centry.model.Keys;
import com.centry.repository.BudgetRepository;
import com.centry.repository.ExpenseRepository;
import com.centry.service.ExpenseService;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Phone-first "add an expense" page. The rules (positive, at most two decimals, length limits) are the ones
 * of {@code POST /api/v1/expenses}: the form builds the same {@link ExpenseEvent} and validates it.
 *
 * <p>With htmx the form posts in place and gets the form panel back; without JavaScript it is a normal
 * post/redirect/get.
 */
@Controller
@RequiredArgsConstructor
class QuickAddController {

    private static final int RECENT_CATEGORIES = 20;
    private static final String HX_REQUEST = "HX-Request";
    private static final String PANEL = "add :: panel";

    private final ExpenseService expenseService;
    private final ExpenseRepository expenses;
    private final BudgetRepository budgets;
    private final Validator validator;
    private final Clock clock;

    @GetMapping("/add")
    String form(@RequestParam Map<String, String> params, Model model) {
        // The post/redirect/get flag is a bare "?saved", which has no value.
        if (params.containsKey("saved")) {
            model.addAttribute("message", "Expense saved.");
        }
        return render(model, QuickAddForm.blank(today(), ""), Map.of(), "add");
    }

    @PostMapping("/add")
    String submit(@ModelAttribute QuickAddForm submitted,
            @RequestHeader(name = HX_REQUEST, required = false) String htmx,
            Model model, HttpServletResponse response) {
        QuickAddForm form = submitted.trimmed();
        Map<String, String> errors = new LinkedHashMap<>();
        ExpenseEvent event = toEvent(form, errors);
        boolean fragment = htmx != null;

        if (!errors.isEmpty()) {
            response.setStatus(422);
            return render(model, form, errors, fragment ? PANEL : "add");
        }
        ExpenseEntity saved = expenseService.record(event, null).expense();
        if (!fragment) {
            return "redirect:/add?saved";
        }
        model.addAttribute("message", "Saved %s at %s (%s).".formatted(saved.amount(), saved.merchant(),
                saved.category()));
        return render(model, QuickAddForm.blank(today(), saved.category()), Map.of(), PANEL);
    }

    private String render(Model model, QuickAddForm form, Map<String, String> errors, String view) {
        model.addAttribute("form", form);
        model.addAttribute("errors", errors);
        model.addAttribute("categories", knownCategories());
        return view;
    }

    private LocalDate today() {
        return LocalDate.now(clock);
    }

    /** Budget categories first, then the ones used lately; one entry per normalized key. */
    private List<String> knownCategories() {
        Map<String, String> byKey = new LinkedHashMap<>();
        budgets.categoryKeys().forEach(key -> byKey.putIfAbsent(key, key));
        expenses.recentCategories(RECENT_CATEGORIES).forEach(name -> byKey.putIfAbsent(Keys.of(name), name));
        return new ArrayList<>(byKey.values());
    }

    /** Builds the event; everything wrong with the input ends up in {@code errors}, keyed by form field. */
    private ExpenseEvent toEvent(QuickAddForm form, Map<String, String> errors) {
        BigDecimal amount = null;
        try {
            amount = new BigDecimal(form.amount());
        } catch (NumberFormatException e) {
            errors.put("amount", "must be a number, like 12.50");
        }
        LocalDate date = today();
        if (!form.date().isEmpty()) {
            try {
                date = LocalDate.parse(form.date());
            } catch (DateTimeParseException e) {
                errors.put("date", "must be a valid date");
            }
        }
        // Today is left to the service, which stamps "now" once when it creates the row. Stamping it here would
        // give a retried request a different timestamp and the service would reject it as a conflicting replay.
        // Any other date is recorded at the start of that day.
        LocalDateTime timestamp = date.equals(today()) ? null : date.atStartOfDay();
        ExpenseEvent event = ExpenseEvent.builder()
                .transactionId(form.clientRef().isEmpty() ? null : form.clientRef())
                .merchant(form.merchant().isEmpty() ? form.category() : form.merchant())
                .amount(amount)
                .category(form.category())
                .timestamp(timestamp)
                .build();
        for (ConstraintViolation<ExpenseEvent> violation : validator.validate(event)) {
            errors.putIfAbsent(violation.getPropertyPath().toString(), violation.getMessage());
        }
        return event;
    }
}
