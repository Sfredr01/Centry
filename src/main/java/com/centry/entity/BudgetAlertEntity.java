package com.centry.entity;

import com.centry.model.BudgetStatus;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public record BudgetAlertEntity(
        Long id,
        String category,
        BigDecimal currentSpend,
        BigDecimal monthlyLimit,
        BigDecimal warningAmount,
        BudgetStatus.Status status,
        String message,
        LocalDateTime timestamp) {
}
