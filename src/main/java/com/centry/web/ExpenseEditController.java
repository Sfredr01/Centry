package com.centry.web;

import com.centry.dto.ExpenseEvent;
import com.centry.entity.ExpenseEntity;
import com.centry.repository.ExpenseRepository;
import com.centry.repository.UserRepository;
import com.centry.security.Actor;
import com.centry.service.ExpenseService;
import com.centry.service.UnknownSpenderException;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.server.ResponseStatusException;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Edit and delete an expense from the browser. Allowed for admins and for whoever created the expense (see
 * {@link Actor#canModify}); everyone else gets 403 here, not just a missing link. Validation is the quick-add
 * one, through {@link ExpenseFormMapper}. Plain forms: post, then redirect back to the dashboard.
 */
@Controller
@RequiredArgsConstructor
class ExpenseEditController {

    private final ExpenseService expenseService;
    private final ExpenseRepository expenses;
    private final UserRepository users;
    private final ExpenseFormMapper mapper;
    private final CategoryChoices categories;
    private final Clock clock;

    @GetMapping("/expenses/{id}/edit")
    String form(@PathVariable long id, Authentication caller, Model model) {
        return render(model, load(id, caller), Map.of(), null);
    }

    @PostMapping("/expenses/{id}")
    String save(@PathVariable long id, @ModelAttribute ExpenseForm submitted, Authentication caller, Model model,
            HttpServletResponse response) {
        ExpenseEntity stored = load(id, caller);
        ExpenseForm form = submitted.trimmed();
        Map<String, String> errors = new LinkedHashMap<>();
        ExpenseEvent event = mapper.toEvent(form, errors);
        if (errors.isEmpty()) {
            event.setTimestamp(timestampFor(event, stored));
            try {
                expenseService.update(stored, event);
                return "redirect:/?edited";
            } catch (UnknownSpenderException e) {
                errors.put("spentBy", ExpenseFormMapper.UNKNOWN_SPENDER);
            }
        }
        response.setStatus(HttpStatus.UNPROCESSABLE_CONTENT.value());
        return render(model, stored, errors, form);
    }

    @PostMapping("/expenses/{id}/delete")
    String delete(@PathVariable long id, Authentication caller) {
        expenses.deleteById(load(id, caller).id());
        return "redirect:/?deleted";
    }

    /** The expense, or 404 if there is none and 403 if the caller may not change it. */
    private ExpenseEntity load(long id, Authentication caller) {
        ExpenseEntity expense = expenses.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        if (!Actor.of(caller, users).canModify(expense)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        }
        return expense;
    }

    /**
     * The form only has a date. Keeping the stored time of day when the date is unchanged stops an edit from
     * reordering same-day expenses; a new date today means "now" and any other new date its start of day.
     */
    private LocalDateTime timestampFor(ExpenseEvent event, ExpenseEntity stored) {
        LocalDateTime chosen = event.getTimestamp() != null ? event.getTimestamp() : LocalDateTime.now(clock);
        return chosen.toLocalDate().equals(stored.timestamp().toLocalDate()) ? stored.timestamp() : chosen;
    }

    /** @param submitted what the user typed after a failed save, or null to show the stored values */
    private String render(Model model, ExpenseEntity stored, Map<String, String> errors, ExpenseForm submitted) {
        model.addAttribute("expenseId", stored.id());
        model.addAttribute("form", submitted != null ? submitted : ExpenseForm.of(stored));
        model.addAttribute("errors", errors);
        model.addAttribute("categories", categories.known());
        model.addAttribute("members", users.findAll());
        return "edit";
    }
}
