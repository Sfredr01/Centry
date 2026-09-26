package com.centry.dto;

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
public class ExpenseEvent {
    private String transactionId;
    private String merchant;
    private BigDecimal amount;
    private String category;
    private LocalDateTime timestamp;
}