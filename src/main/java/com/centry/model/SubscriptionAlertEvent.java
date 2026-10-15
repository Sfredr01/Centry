package com.centry.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SubscriptionAlertEvent {
    private String merchant;
    private BigDecimal previousAmount;
    private BigDecimal currentAmount;
    private String anomalyType; // e.g. "PRICE_INCREASE", "CADENCE_SHIFT"
    private String message;
    private LocalDateTime timestamp;
}
