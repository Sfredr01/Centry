package com.centry.entity;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public record ExpenseEntity(
        Long id,
        String transactionId,
        String merchant,
        BigDecimal amount,
        String category,
        LocalDateTime timestamp) {
}
