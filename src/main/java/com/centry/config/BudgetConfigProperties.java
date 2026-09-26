package com.centry.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;

@Configuration
@ConfigurationProperties(prefix = "centry.budget")
public class BudgetConfigProperties {

    /**
     * Map of category names (lowercase or normalized) to their monthly limit & warning threshold.
     */
    private Map<String, CategoryLimit> categories = new HashMap<>();

    public Map<String, CategoryLimit> getCategories() {
        return categories;
    }

    public void setCategories(Map<String, CategoryLimit> categories) {
        this.categories = categories;
    }

    public static class CategoryLimit {
        private BigDecimal monthlyLimit;
        private double warningThresholdPercent = 0.80; // Default warning at 80%

        public CategoryLimit() {}

        public CategoryLimit(BigDecimal monthlyLimit, double warningThresholdPercent) {
            this.monthlyLimit = monthlyLimit;
            this.warningThresholdPercent = warningThresholdPercent;
        }

        public BigDecimal getMonthlyLimit() {
            return monthlyLimit;
        }

        public void setMonthlyLimit(BigDecimal monthlyLimit) {
            this.monthlyLimit = monthlyLimit;
        }

        public double getWarningThresholdPercent() {
            return warningThresholdPercent;
        }

        public void setWarningThresholdPercent(double warningThresholdPercent) {
            this.warningThresholdPercent = warningThresholdPercent;
        }

        public BigDecimal getWarningAmount() {
            if (monthlyLimit == null) return BigDecimal.ZERO;
            return monthlyLimit.multiply(BigDecimal.valueOf(warningThresholdPercent));
        }
    }
}