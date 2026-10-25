package com.centry.service;

import com.centry.model.SubscriptionAlertEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

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

    @Test
    void nullMerchantOrAmountReturnsNullAndRecordsNothing() {
        assertNull(subscriptionTrackingService.processAndCheckSubscription(null, BigDecimal.TEN, LocalDateTime.now()));
        assertNull(subscriptionTrackingService.processAndCheckSubscription("Netflix", null, LocalDateTime.now()));
        assertTrue(subscriptionTrackingService.getMerchantHistory("Netflix").isEmpty());
    }

    @Test
    void nullTimestampDefaultsToNow() {
        LocalDateTime before = LocalDateTime.now();
        subscriptionTrackingService.processAndCheckSubscription("Netflix", BigDecimal.TEN, null);

        List<SubscriptionTrackingService.TransactionRecord> history = subscriptionTrackingService.getMerchantHistory("Netflix");
        assertEquals(1, history.size());
        assertFalse(history.get(0).timestamp().isBefore(before));
    }

    @Test
    void equalAmountProducesNoAlert() {
        LocalDateTime t = LocalDateTime.now();
        subscriptionTrackingService.processAndCheckSubscription("Netflix", new BigDecimal("15.49"), t.minusDays(30));
        assertNull(subscriptionTrackingService.processAndCheckSubscription("Netflix", new BigDecimal("15.49"), t));
        assertEquals(2, subscriptionTrackingService.getMerchantHistory("Netflix").size());
    }

    @Test
    void lowerAmountProducesNoAlert() {
        LocalDateTime t = LocalDateTime.now();
        subscriptionTrackingService.processAndCheckSubscription("Netflix", new BigDecimal("15.49"), t.minusDays(30));
        assertNull(subscriptionTrackingService.processAndCheckSubscription("Netflix", new BigDecimal("9.99"), t));
    }

    @Test
    void getMerchantHistoryNullOrUnknownIsEmpty() {
        assertTrue(subscriptionTrackingService.getMerchantHistory(null).isEmpty());
        assertTrue(subscriptionTrackingService.getMerchantHistory("Nobody").isEmpty());
    }

    @Test
    void merchantNamesAreNormalizedAcrossCaseAndWhitespace() {
        LocalDateTime t = LocalDateTime.now();
        subscriptionTrackingService.processAndCheckSubscription("Netflix", new BigDecimal("15.49"), t.minusDays(30));
        SubscriptionAlertEvent alert = subscriptionTrackingService.processAndCheckSubscription(
                "  NETFLIX ", new BigDecimal("17.99"), t);

        assertNotNull(alert);
        assertEquals("  NETFLIX ", alert.getMerchant());
        List<SubscriptionTrackingService.TransactionRecord> history = subscriptionTrackingService.getMerchantHistory(" netflix");
        assertEquals(2, history.size());
        assertEquals(new BigDecimal("15.49"), history.get(0).amount());
        assertEquals(new BigDecimal("17.99"), history.get(1).amount());
    }
}
