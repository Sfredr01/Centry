package com.centry.service;

import com.centry.model.SubscriptionAlertEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class SubscriptionTrackingService {

    private static final Logger log = LoggerFactory.getLogger(SubscriptionTrackingService.class);

    // Merchant name (normalized) -> List of transaction records
    private final Map<String, List<TransactionRecord>> merchantHistory = new ConcurrentHashMap<>();

    public record TransactionRecord(LocalDateTime timestamp, BigDecimal amount) {}

    /**
     * Tracks transaction history, evaluates cadence, and detects price anomalies.
     * Returns a SubscriptionAlertEvent if an anomaly (price increase) is detected, otherwise null.
     */
    public SubscriptionAlertEvent processAndCheckSubscription(String merchant, BigDecimal amount, LocalDateTime timestamp) {
        if (merchant == null || amount == null) {
            return null;
        }

        String normalizedMerchant = merchant.trim().toLowerCase();
        LocalDateTime eventTime = timestamp != null ? timestamp : LocalDateTime.now();

        List<TransactionRecord> history = merchantHistory.computeIfAbsent(normalizedMerchant, k -> Collections.synchronizedList(new ArrayList<>()));

        SubscriptionAlertEvent alertEvent = null;

        synchronized (history) {
            if (!history.isEmpty()) {
                TransactionRecord lastTransaction = history.get(history.size() - 1);
                long daysBetween = Duration.between(lastTransaction.timestamp(), eventTime).toDays();

                log.info("Merchant '{}': previous charge ${} on {}, current charge ${} on {} ({} days apart)",
                        merchant, lastTransaction.amount(), lastTransaction.timestamp(), amount, eventTime, daysBetween);

                // Price anomaly check: compare current amount with previous amount
                if (amount.compareTo(lastTransaction.amount()) > 0) {
                    BigDecimal increase = amount.subtract(lastTransaction.amount());
                    String msg = String.format("Price increase detected for subscription [%s]: jumped from $%.2f to $%.2f (+$%.2f)",
                            merchant, lastTransaction.amount(), amount, increase);

                    log.warn("Subscription Price Anomaly: {}", msg);
                    alertEvent = SubscriptionAlertEvent.builder()
                            .merchant(merchant)
                            .previousAmount(lastTransaction.amount())
                            .currentAmount(amount)
                            .anomalyType("PRICE_INCREASE")
                            .message(msg)
                            .timestamp(eventTime)
                            .build();
                }
            } else {
                log.info("First recorded transaction for merchant '{}' at ${}", merchant, amount);
            }

            // Record current transaction
            history.add(new TransactionRecord(eventTime, amount));
        }

        return alertEvent;
    }

    public List<TransactionRecord> getMerchantHistory(String merchant) {
        if (merchant == null) return Collections.emptyList();
        return merchantHistory.getOrDefault(merchant.trim().toLowerCase(), Collections.emptyList());
    }
}
