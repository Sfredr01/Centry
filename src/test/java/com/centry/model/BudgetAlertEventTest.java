package com.centry.model;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.cfg.DateTimeFeature;
import tools.jackson.databind.json.JsonMapper;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BudgetAlertEventTest {

    private final ObjectMapper mapper = JsonMapper.builder()
            .disable(DateTimeFeature.WRITE_DATES_AS_TIMESTAMPS)
            .build();

    private BudgetAlertEvent sample() {
        return new BudgetAlertEvent("food", new BigDecimal("120.50"), new BigDecimal("100.00"),
                new BigDecimal("80.00"), BudgetLevel.LIMIT_EXCEEDED, "Over");
    }

    @Test
    void noArgConstructorSetsTimestampOnly() {
        Instant before = Instant.now();
        BudgetAlertEvent e = new BudgetAlertEvent();
        Instant after = Instant.now();

        assertNotNull(e.getTimestamp());
        assertTrue(!e.getTimestamp().isBefore(before) && !e.getTimestamp().isAfter(after));
        assertNull(e.getCategory());
        assertNull(e.getCurrentSpend());
        assertNull(e.getMonthlyLimit());
        assertNull(e.getWarningThreshold());
        assertNull(e.getAlertLevel());
        assertNull(e.getMessage());
    }

    @Test
    void fullConstructorPopulatesFieldsAndTimestamp() {
        BudgetAlertEvent e = sample();
        assertEquals("food", e.getCategory());
        assertEquals(new BigDecimal("120.50"), e.getCurrentSpend());
        assertEquals(new BigDecimal("100.00"), e.getMonthlyLimit());
        assertEquals(new BigDecimal("80.00"), e.getWarningThreshold());
        assertEquals(BudgetLevel.LIMIT_EXCEEDED, e.getAlertLevel());
        assertEquals("Over", e.getMessage());
        assertNotNull(e.getTimestamp());
    }

    @Test
    void settersOverrideValues() {
        BudgetAlertEvent e = new BudgetAlertEvent();
        Instant ts = Instant.parse("2024-01-02T03:04:05Z");
        e.setCategory("rent");
        e.setCurrentSpend(BigDecimal.ONE);
        e.setMonthlyLimit(BigDecimal.TEN);
        e.setWarningThreshold(BigDecimal.TWO);
        e.setAlertLevel(BudgetLevel.WARNING_EXCEEDED);
        e.setMessage("hi");
        e.setTimestamp(ts);

        assertEquals("rent", e.getCategory());
        assertEquals(BigDecimal.ONE, e.getCurrentSpend());
        assertEquals(BigDecimal.TEN, e.getMonthlyLimit());
        assertEquals(BigDecimal.TWO, e.getWarningThreshold());
        assertEquals(BudgetLevel.WARNING_EXCEEDED, e.getAlertLevel());
        assertEquals("hi", e.getMessage());
        assertEquals(ts, e.getTimestamp());
    }

    @Test
    void jsonRoundTripPreservesPayload() throws Exception {
        BudgetAlertEvent original = sample();
        original.setTimestamp(Instant.parse("2024-05-06T07:08:09Z"));

        String json = mapper.writeValueAsString(original);
        BudgetAlertEvent copy = mapper.readValue(json, BudgetAlertEvent.class);

        assertEquals(original.getCategory(), copy.getCategory());
        assertEquals(0, original.getCurrentSpend().compareTo(copy.getCurrentSpend()));
        assertEquals(0, original.getMonthlyLimit().compareTo(copy.getMonthlyLimit()));
        assertEquals(0, original.getWarningThreshold().compareTo(copy.getWarningThreshold()));
        assertEquals(original.getAlertLevel(), copy.getAlertLevel());
        assertEquals(original.getMessage(), copy.getMessage());
        assertEquals(original.getTimestamp(), copy.getTimestamp());
    }
}
