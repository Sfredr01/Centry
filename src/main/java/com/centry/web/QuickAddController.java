package com.centry.web;

import com.centry.dto.ExpenseEvent;
import com.centry.entity.ExpenseEntity;
import com.centry.repository.UserRepository;
import com.centry.security.Actor;
import com.centry.service.ExpenseService;
import com.centry.service.UnknownSpenderException;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.LinkedHashMap;
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

    private static final String HX_REQUEST = "HX-Request";
    private static final String PANEL = "add :: panel";

    private final ExpenseService expenseService;
    private final CategoryChoices categories;
    private final UserRepository users;
    private final ExpenseFormMapper mapper;
    private final Money money;

    @GetMapping("/add")
    String form(@RequestParam Map<String, String> params, Authentication caller, Model model) {
        // The post/redirect/get flag is a bare "?saved", which has no value.
        if (params.containsKey("saved")) {
            model.addAttribute("message", "Expense saved.");
        }
        return render(model, ExpenseForm.blank(mapper.today(), "", myId(caller)), Map.of(), "add");
    }

    @PostMapping("/add")
    String submit(@ModelAttribute ExpenseForm submitted,
            @RequestHeader(name = HX_REQUEST, required = false) String htmx,
            Authentication caller, Model model, HttpServletResponse response) {
        ExpenseForm form = submitted.trimmed();
        Map<String, String> errors = new LinkedHashMap<>();
        ExpenseEvent event = mapper.toEvent(form, errors);
        boolean fragment = htmx != null;

        if (!errors.isEmpty()) {
            response.setStatus(422);
            return render(model, form, errors, fragment ? PANEL : "add");
        }
        ExpenseEntity saved;
        try {
            saved = expenseService.record(event, Actor.of(caller, users).userId()).expense();
        } catch (UnknownSpenderException e) {
            response.setStatus(422);
            return render(model, form, Map.of("spentBy", ExpenseFormMapper.UNKNOWN_SPENDER), fragment ? PANEL : "add");
        }
        if (!fragment) {
            return "redirect:/add?saved";
        }
        model.addAttribute("message", "Saved %s at %s (%s).".formatted(money.format(saved.amount()),
                saved.merchant(), saved.category()));
        return render(model, ExpenseForm.blank(mapper.today(), saved.category(), form.spentBy()), Map.of(), PANEL);
    }

    private String render(Model model, ExpenseForm form, Map<String, String> errors, String view) {
        model.addAttribute("form", form);
        model.addAttribute("errors", errors);
        model.addAttribute("categories", categories.known());
        model.addAttribute("members", users.findAll());
        return view;
    }

    private String myId(Authentication caller) {
        Long id = Actor.of(caller, users).userId();
        return id == null ? "" : id.toString();
    }
}
