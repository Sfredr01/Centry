package com.centry.web;

import com.centry.model.Keys;
import com.centry.model.SubscriptionCadence;
import com.centry.model.SubscriptionStatus;
import com.centry.repository.SubscriptionRepository;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;

/**
 * Subscriptions page. Everyone signed in can read it; the forms are only rendered for admins and the POST routes
 * are admin-only in {@code SecurityConfig}, like budgets. Plain forms with post/redirect/get.
 */
@Controller
@RequiredArgsConstructor
class SubscriptionsPageController {

    private static final String REDIRECT = "redirect:/subscriptions";
    // Money is stored as integer cents; nine integer digits is the budget API's limit too.
    private static final int MAX_INTEGER_DIGITS = 9;

    private final SubscriptionRepository subscriptions;
    private final Clock clock;

    /** The values of a submitted form once they have been checked. */
    private record Fields(String name, SubscriptionCadence cadence, BigDecimal amount, LocalDate nextDue) {
    }

    /** A problem with the submitted form, shown to the user. */
    private static final class Rejected extends Exception {
        Rejected(String message) {
            super(message);
        }
    }

    @GetMapping("/subscriptions")
    String list(Model model) {
        return render(model);
    }

    @PostMapping("/subscriptions")
    String add(@RequestParam(defaultValue = "") String name, @RequestParam(defaultValue = "") String merchant,
            @RequestParam(defaultValue = "") String cadence, @RequestParam(defaultValue = "") String amount,
            @RequestParam(defaultValue = "") String nextDue, Model model, HttpServletResponse response) {
        try {
            Fields fields = parse(name, cadence, amount, nextDue);
            // The merchant text decides which charges match; it defaults to the name.
            String key = Keys.of(merchant.strip().isEmpty() ? fields.name() : merchant.strip());
            LocalDateTime now = LocalDateTime.now(clock);
            subscriptions.upsertConfirmed(key, fields.name(), fields.cadence(), fields.amount(), fields.nextDue(),
                    now.toLocalDate(), now);
            return REDIRECT;
        } catch (Rejected e) {
            return rejected(e, model, response);
        }
    }

    @PostMapping("/subscriptions/{id}")
    String edit(@PathVariable long id, @RequestParam(defaultValue = "") String name,
            @RequestParam(defaultValue = "") String cadence, @RequestParam(defaultValue = "") String amount,
            @RequestParam(defaultValue = "") String nextDue, Model model, HttpServletResponse response) {
        try {
            Fields fields = parse(name, cadence, amount, nextDue);
            found(subscriptions.update(id, fields.name(), fields.cadence(), fields.amount(), fields.nextDue(),
                    today()));
            return REDIRECT;
        } catch (Rejected e) {
            return rejected(e, model, response);
        }
    }

    @PostMapping("/subscriptions/{id}/confirm")
    String confirm(@PathVariable long id) {
        found(subscriptions.confirm(id, today()));
        return REDIRECT;
    }

    @PostMapping("/subscriptions/{id}/ignore")
    String ignore(@PathVariable long id) {
        found(subscriptions.ignore(id, today()));
        return REDIRECT;
    }

    @PostMapping("/subscriptions/{id}/delete")
    String delete(@PathVariable long id) {
        // Idempotent: a subscription that is already gone is the state the user asked for.
        subscriptions.delete(id);
        return REDIRECT;
    }

    private Fields parse(String nameText, String cadenceText, String amountText, String nextDueText)
            throws Rejected {
        // The key is built from the stripped name, so "Netflix" and " Netflix " are one subscription.
        String name = nameText.strip();
        if (Keys.of(name).isEmpty()) {
            throw new Rejected("Name is required.");
        }
        SubscriptionCadence cadence;
        try {
            cadence = SubscriptionCadence.valueOf(cadenceText);
        } catch (IllegalArgumentException e) {
            throw new Rejected("Pick monthly or yearly.");
        }
        return new Fields(name, cadence, amount(amountText), nextDue(nextDueText));
    }

    private static BigDecimal amount(String text) throws Rejected {
        Rejected bad = new Rejected("Amount must be a positive number with at most two decimals, like 15.49.");
        BigDecimal amount;
        try {
            amount = new BigDecimal(text.strip());
        } catch (NumberFormatException e) {
            throw bad;
        }
        BigDecimal normal = amount.stripTrailingZeros();
        if (amount.signum() <= 0 || normal.scale() > 2 || normal.precision() - normal.scale() > MAX_INTEGER_DIGITS) {
            throw bad;
        }
        return amount;
    }

    private static LocalDate nextDue(String text) throws Rejected {
        try {
            return LocalDate.parse(text.strip());
        } catch (DateTimeParseException e) {
            throw new Rejected("Next due date must be a date, like 2026-04-04.");
        }
    }

    /** An id that matches nothing is a 404, so a stale page cannot look like it worked. */
    private static void found(boolean changed) {
        if (!changed) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
    }

    private LocalDate today() {
        return LocalDate.now(clock);
    }

    private String rejected(Rejected problem, Model model, HttpServletResponse response) {
        response.setStatus(HttpStatus.UNPROCESSABLE_CONTENT.value());
        model.addAttribute("error", problem.getMessage());
        return render(model);
    }

    private String render(Model model) {
        model.addAttribute("candidates", subscriptions.findByStatus(SubscriptionStatus.CANDIDATE));
        model.addAttribute("confirmed", subscriptions.findByStatus(SubscriptionStatus.CONFIRMED));
        model.addAttribute("ignored", subscriptions.findByStatus(SubscriptionStatus.IGNORED));
        model.addAttribute("annualTotal", subscriptions.annualTotal());
        model.addAttribute("today", today());
        return "subscriptions";
    }
}
