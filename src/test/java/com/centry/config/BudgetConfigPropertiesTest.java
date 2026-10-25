package com.centry.config;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class BudgetConfigPropertiesTest {

    @Test
    void webhookUrlDefaultsToNull() {
        assertNull(new BudgetConfigProperties().getWebhookUrl());
    }

    @Test
    void webhookUrlRoundTrips() {
        BudgetConfigProperties props = new BudgetConfigProperties();
        props.setWebhookUrl("http://example.test/hook");

        assertEquals("http://example.test/hook", props.getWebhookUrl());
    }
}
