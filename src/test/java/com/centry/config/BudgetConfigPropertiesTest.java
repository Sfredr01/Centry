package com.centry.config;

import com.centry.config.BudgetConfigProperties.CategoryLimit;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BudgetConfigPropertiesTest {

    @Test
    void defaultsAreEmpty() {
        BudgetConfigProperties props = new BudgetConfigProperties();
        assertNull(props.getWebhookUrl());
        assertTrue(props.getCategories().isEmpty());
    }

    @Test
    void webhookUrlAndCategoriesRoundTrip() {
        BudgetConfigProperties props = new BudgetConfigProperties();
        props.setWebhookUrl("http://example.test/hook");
        Map<String, CategoryLimit> map = new HashMap<>();
        map.put("food", new CategoryLimit(new BigDecimal("100"), 0.5));
        props.setCategories(map);

        assertEquals("http://example.test/hook", props.getWebhookUrl());
        assertSame(map, props.getCategories());
    }

    @Test
    void categoryLimitDefaultsToEightyPercent() {
        CategoryLimit limit = new CategoryLimit();
        assertNull(limit.getMonthlyLimit());
        assertEquals(0.80, limit.getWarningThresholdPercent());
    }

    @Test
    void categoryLimitConstructorAndSetters() {
        CategoryLimit limit = new CategoryLimit(new BigDecimal("200"), 0.25);
        assertEquals(0, new BigDecimal("200").compareTo(limit.getMonthlyLimit()));
        assertEquals(0.25, limit.getWarningThresholdPercent());

        limit.setMonthlyLimit(new BigDecimal("300"));
        limit.setWarningThresholdPercent(0.9);
        assertEquals(0, new BigDecimal("300").compareTo(limit.getMonthlyLimit()));
        assertEquals(0.9, limit.getWarningThresholdPercent());
    }

    @Test
    void warningAmountIsZeroWithoutLimit() {
        assertEquals(0, BigDecimal.ZERO.compareTo(new CategoryLimit().getWarningAmount()));
    }

    @Test
    void warningAmountIsLimitTimesPercent() {
        CategoryLimit limit = new CategoryLimit(new BigDecimal("200"), 0.75);
        assertEquals(0, new BigDecimal("150").compareTo(limit.getWarningAmount()));
    }
}
