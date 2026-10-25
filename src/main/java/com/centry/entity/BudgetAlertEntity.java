package com.centry.entity;

import com.centry.model.BudgetStatus;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(name = "budget_alerts")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BudgetAlertEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String category;
    private BigDecimal currentSpend;
    private BigDecimal monthlyLimit;
    private BigDecimal warningAmount;

    @Enumerated(EnumType.STRING)
    private BudgetStatus.Status status;

    @Column(length = 1000)
    private String message;

    private LocalDateTime timestamp;
}
