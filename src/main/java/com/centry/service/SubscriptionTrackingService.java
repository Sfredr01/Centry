package com.centry.service;

import com.centry.entity.ExpenseEntity;
import com.centry.model.Keys;
import com.centry.model.SubscriptionAlertEvent;
import com.centry.repository.ExpenseRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.Optional;

@Service
public class SubscriptionTrackingService {

    private static final Logger log = LoggerFactory.getLogger(SubscriptionTrackingService.class);

    private final ExpenseRepository expenseRepository;

    public SubscriptionTrackingService(ExpenseRepository expenseRepository) {
        this.expenseRepository = expenseRepository;
    }

    /**
     * Compares a just-stored expense with the merchant's previous one (same normalized merchant, latest by id,
     * read from the database) and returns an alert if the amount went up.
     */
    public Optional<SubscriptionAlertEvent> checkPriceIncrease(ExpenseEntity current) {
        return expenseRepository.findPreviousForMerchant(Keys.of(current.merchant()), current.id())
                .filter(previous -> current.amount().compareTo(previous.amount()) > 0)
                .map(previous -> {
                    BigDecimal increase = current.amount().subtract(previous.amount());
                    String msg = String.format(
                            "Price increase detected for subscription [%s]: jumped from $%.2f to $%.2f (+$%.2f)",
                            current.merchant(), previous.amount(), current.amount(), increase);
                    log.warn("Subscription Price Anomaly: {}", msg);
                    return SubscriptionAlertEvent.builder()
                            .merchant(current.merchant())
                            .previousAmount(previous.amount())
                            .currentAmount(current.amount())
                            .anomalyType("PRICE_INCREASE")
                            .message(msg)
                            .timestamp(current.timestamp())
                            .build();
                });
    }
}
