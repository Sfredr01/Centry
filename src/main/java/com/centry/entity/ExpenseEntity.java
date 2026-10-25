package com.centry.entity;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * @param spentBy   the household member the expense is attributed to; null when unknown
 * @param createdBy the logged-in user who recorded it, which decides who may edit or delete it; null for rows
 *                  that came from Kafka or from before attribution existed
 */
public record ExpenseEntity(
        Long id,
        String transactionId,
        String merchant,
        BigDecimal amount,
        String category,
        LocalDateTime timestamp,
        Long spentBy,
        Long createdBy) {
}
