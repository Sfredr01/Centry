package com.centry.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(name = "subscription_alerts")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SubscriptionAlertEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String merchant;
    private BigDecimal previousAmount;
    private BigDecimal currentAmount;
    private String anomalyType;

    @Column(length = 1000)
    private String message;

    private LocalDateTime timestamp;
}
