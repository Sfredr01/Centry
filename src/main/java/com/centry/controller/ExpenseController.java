package com.centry.controller;

import com.centry.dto.ExpenseEvent;
import com.centry.service.ExpenseService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/expenses")
@RequiredArgsConstructor
public class ExpenseController {

    private final ExpenseService expenseService;

    @PostMapping
    public ResponseEntity<String> createExpense(@RequestBody ExpenseEvent expense) {
        // Assign a unique transaction ID and timestamp if not provided
        if (expense.getTransactionId() == null) {
            expense.setTransactionId(UUID.randomUUID().toString());
        }
        if (expense.getTimestamp() == null) {
            expense.setTimestamp(LocalDateTime.now());
        }

        expenseService.record(expense);
        return ResponseEntity.ok("Expense recorded.");
    }
}