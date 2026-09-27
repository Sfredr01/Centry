package com.centry.model;

import java.math.BigDecimal;
import java.time.Instant;

public class BudgetAlertEvent {

    private String category;
    private BigDecimal currentSpend;
    private BigDecimal monthlyLimit;
    private BigDecimal warningThreshold;
    private BudgetStatus.Status alertLevel; // WARNING_EXCEEDED or LIMIT_EXCEEDED
    private String message;
    private Instant timestamp;

    public BudgetAlertEvent() {
        this.timestamp = Instant.now();
    }

    public BudgetAlertEvent(String category, BigDecimal currentSpend, BigDecimal monthlyLimit, 
                            BigDecimal warningThreshold, BudgetStatus.Status alertLevel, String message) {
        this.category = category;
        this.currentSpend = currentSpend;
        this.monthlyLimit = monthlyLimit;
        this.warningThreshold = warningThreshold;
        this.alertLevel = alertLevel;
        this.message = message;
        this.timestamp = Instant.now();
    }

    // Getters and Setters
    public String getCategory() { return category; }
    public void setCategory(String category) { this.category = category; }

    public BigDecimal getCurrentSpend() { return currentSpend; }
    public void setCurrentSpend(BigDecimal currentSpend) { this.currentSpend = currentSpend; }

    public BigDecimal getMonthlyLimit() { return monthlyLimit; }
    public void setMonthlyLimit(BigDecimal monthlyLimit) { this.monthlyLimit = monthlyLimit; }

    public BigDecimal getWarningThreshold() { return warningThreshold; }
    public void setWarningThreshold(BigDecimal warningThreshold) { this.warningThreshold = warningThreshold; }

    public BudgetStatus.Status getAlertLevel() { return alertLevel; }
    public void setAlertLevel(BudgetStatus.Status alertLevel) { this.alertLevel = alertLevel; }

    public String getMessage() { return message; }
    public void setMessage(String message) { this.message = message; }

    public Instant getTimestamp() { return timestamp; }
    public void setTimestamp(Instant timestamp) { this.timestamp = timestamp; }
}