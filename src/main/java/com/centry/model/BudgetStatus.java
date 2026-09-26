package com.centry.model;

import java.math.BigDecimal;

public class BudgetStatus {
    private final String category;
    private final BigDecimal currentSpend;
    private final BigDecimal monthlyLimit;
    private final BigDecimal warningAmount;
    private final Status status;

    public enum Status {
        OK,
        WARNING_EXCEEDED,
        LIMIT_EXCEEDED
    }

    public BudgetStatus(String category, BigDecimal currentSpend, BigDecimal monthlyLimit, BigDecimal warningAmount, Status status) {
        this.category = category;
        this.currentSpend = currentSpend;
        this.monthlyLimit = monthlyLimit;
        this.warningAmount = warningAmount;
        this.status = status;
    }

    public String getCategory() { return category; }
    public BigDecimal getCurrentSpend() { return currentSpend; }
    public BigDecimal getMonthlyLimit() { return monthlyLimit; }
    public BigDecimal getWarningAmount() { return warningAmount; }
    public Status getStatus() { return status; }
}