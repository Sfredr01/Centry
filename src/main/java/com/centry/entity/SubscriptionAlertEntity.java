package com.centry.entity;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public record SubscriptionAlertEntity(
        Long id,
        String merchant,
        BigDecimal previousAmount,
        BigDecimal currentAmount,
        String anomalyType,
        String message,
        LocalDateTime timestamp) {
}
