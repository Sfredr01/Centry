package com.centry.controller;

import com.centry.dto.ExpenseEvent;
import com.centry.entity.ExpenseEntity;
import com.centry.repository.ExpenseRepository;
import com.centry.repository.UserRepository;
import com.centry.security.Actor;
import com.centry.service.ExpenseService;
import com.centry.service.ExpenseService.Recorded;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.net.URI;

@RestController
@RequestMapping("/api/v1/expenses")
@RequiredArgsConstructor
public class ExpenseController {

    private final ExpenseService expenseService;
    private final ExpenseRepository expenseRepository;
    private final UserRepository users;

    @PostMapping
    public ResponseEntity<ExpenseEntity> createExpense(@Valid @RequestBody ExpenseEvent expense, Authentication caller) {
        // The service assigns a missing transaction id and defaults a missing timestamp from the Clock.
        Recorded recorded = expenseService.record(expense, Actor.of(caller, users).userId());
        URI location = location(recorded.expense());
        // A replay of an already stored expense is not a new resource: 200 instead of 201.
        return recorded.created()
                ? ResponseEntity.created(location).body(recorded.expense())
                : ResponseEntity.ok().location(location).body(recorded.expense());
    }

    @GetMapping("/{id}")
    public ExpenseEntity getExpense(@PathVariable long id) {
        return expenseRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Expense " + id + " not found"));
    }

    private static URI location(ExpenseEntity expense) {
        return URI.create("/api/v1/expenses/" + expense.id());
    }
}
