package com.centry.service;

import com.centry.model.SubscriptionAlertEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.*;

class SubscriptionTrackingServiceTest {

    private SubscriptionTrackingService subscriptionTrackingService;

    @BeforeEach
    void setUp() {
        subscriptionTrackingService = new SubscriptionTrackingService();
    }

    @Test
    void testFirstTransactionNoAlert() {
        SubscriptionAlertEvent alert = subscriptionTrackingService.processAndCheckSubscription(
                "Netflix", BigDecimal.valueOf(15.49), LocalDateTime.now());
        assertNull(alert);
    }

    @Test
    void testPriceIncreaseAnomaly() {
        LocalDateTime t1 = LocalDateTime.now().minusDays(30);
        LocalDateTime t2 = LocalDateTime.now();

        // First charge
        subscriptionTrackingService.processAndCheckSubscription("Netflix", BigDecimal.valueOf(15.49), t1);

        // Second charge with higher amount (price increase)
        SubscriptionAlertEvent alert = subscriptionTrackingService.processAndCheckSubscription(
                "Netflix", BigDecimal.valueOf(17.99), t2);

        assertNotNull(alert);
        assertEquals("PRICE_INCREASE", alert.getAnomalyType());
        assertEquals(BigDecimal.valueOf(15.49), alert.getPreviousAmount());
        assertEquals(BigDecimal.valueOf(17.99), alert.getCurrentAmount());
        assertEquals("Netflix", alert.getMerchant());
    }
}
