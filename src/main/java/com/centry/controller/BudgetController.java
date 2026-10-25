package com.centry.controller;

import com.centry.dto.BudgetRequest;
import com.centry.model.BudgetStatus;
import com.centry.model.Keys;
import com.centry.repository.BudgetRepository;
import com.centry.repository.BudgetStatusQuery;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.time.Clock;
import java.time.YearMonth;
import java.util.List;

/** Monthly budgets per category. Status is always for the current month of the app clock. */
@RestController
@RequestMapping("/api/v1/budgets")
@RequiredArgsConstructor
public class BudgetController {

    public static final int DEFAULT_WARN_PERCENT = 80;

    private final BudgetRepository budgetRepository;
    private final BudgetStatusQuery statusQuery;
    private final Clock clock;

    @PutMapping("/{category}")
    public BudgetStatus put(@PathVariable String category, @Valid @RequestBody BudgetRequest request) {
        String key = Keys.of(category);
        int warnPercent = request.warnPercent() == null ? DEFAULT_WARN_PERCENT : request.warnPercent();
        budgetRepository.upsert(key, request.monthlyLimit(), warnPercent);
        return statusQuery.status(key, YearMonth.now(clock)).orElseThrow();
    }

    @GetMapping
    public List<BudgetStatus> list() {
        return statusQuery.all(YearMonth.now(clock));
    }

    @DeleteMapping("/{category}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable String category) {
        if (!budgetRepository.delete(Keys.of(category))) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Budget " + category + " not found");
        }
    }
}
