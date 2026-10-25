package com.centry.model;

import java.math.BigDecimal;
import java.time.Instant;

public class BudgetAlertEvent {

    private String category;
    private BigDecimal currentSpend;
    private BigDecimal monthlyLimit;
    private BigDecimal warningThreshold;
    private BudgetLevel alertLevel; // WARNING_EXCEEDED or LIMIT_EXCEEDED
    private String message;
    private Instant timestamp;
    private Long alertId; // budget_alert row id, so delivery can be tracked

    public BudgetAlertEvent() {
        this.timestamp = Instant.now();
    }

    public BudgetAlertEvent(String category, BigDecimal currentSpend, BigDecimal monthlyLimit, 
                            BigDecimal warningThreshold, BudgetLevel alertLevel, String message) {
        this.category = category;
        this.currentSpend = currentSpend;
        this.monthlyLimit = monthlyLimit;
        this.warningThreshold = warningThreshold;
        this.alertLevel = alertLevel;
        this.message = message;
        this.timestamp = Instant.now();
    }

    // Getters and Setters
    public Long getAlertId() { return alertId; }
    public void setAlertId(Long alertId) { this.alertId = alertId; }

    public String getCategory() { return category; }
    public void setCategory(String category) { this.category = category; }

    public BigDecimal getCurrentSpend() { return currentSpend; }
    public void setCurrentSpend(BigDecimal currentSpend) { this.currentSpend = currentSpend; }

    public BigDecimal getMonthlyLimit() { return monthlyLimit; }
    public void setMonthlyLimit(BigDecimal monthlyLimit) { this.monthlyLimit = monthlyLimit; }

    public BigDecimal getWarningThreshold() { return warningThreshold; }
    public void setWarningThreshold(BigDecimal warningThreshold) { this.warningThreshold = warningThreshold; }

    public BudgetLevel getAlertLevel() { return alertLevel; }
    public void setAlertLevel(BudgetLevel alertLevel) { this.alertLevel = alertLevel; }

    public String getMessage() { return message; }
    public void setMessage(String message) { this.message = message; }

    public Instant getTimestamp() { return timestamp; }
    public void setTimestamp(Instant timestamp) { this.timestamp = timestamp; }
}