package com.centry.entity;

import com.centry.model.BudgetLevel;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public record BudgetAlertEntity(
        Long id,
        String category,
        BigDecimal currentSpend,
        BigDecimal monthlyLimit,
        BigDecimal warningAmount,
        BudgetLevel status,
        String message,
        LocalDateTime timestamp) {
}
