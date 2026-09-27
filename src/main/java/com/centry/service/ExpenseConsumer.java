package com.centry.service;

import com.centry.config.KafkaTopicConfig;
import com.centry.model.BudgetAlertEvent;
import com.centry.model.BudgetStatus;
import com.centry.dto.ExpenseEvent; // Adjust to match your existing event model name
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

@Service
public class ExpenseConsumer {

    private static final Logger log = LoggerFactory.getLogger(ExpenseConsumer.class);

    private final BudgetTrackingService budgetTrackingService;
    private final KafkaTemplate<String, Object> kafkaTemplate;

    public ExpenseConsumer(BudgetTrackingService budgetTrackingService, KafkaTemplate<String, Object> kafkaTemplate) {
        this.budgetTrackingService = budgetTrackingService;
        this.kafkaTemplate = kafkaTemplate;
    }

    @KafkaListener(topics = "raw-expenses", groupId = "centry-group")
    public void consumeRawExpense(ExpenseEvent event) {
        log.info("Successfully received event from Kafka [raw-expenses]: merchant='{}', amount=${}, category='{}'",
                event.getMerchant(), event.getAmount(), event.getCategory());

        // Update in-memory budget state
        BudgetStatus status = budgetTrackingService.recordExpense(event.getCategory(), event.getAmount());

        if (status != null && status.getStatus() != BudgetStatus.Status.OK) {
            String msg = String.format("Budget alert for [%s]: Current spend $%.2f exceeds %s of $%.2f",
                    status.getCategory(),
                    status.getCurrentSpend(),
                    status.getStatus() == BudgetStatus.Status.LIMIT_EXCEEDED ? "monthly limit" : "warning threshold",
                    status.getStatus() == BudgetStatus.Status.LIMIT_EXCEEDED ? status.getMonthlyLimit() : status.getWarningAmount());

            BudgetAlertEvent alertEvent = new BudgetAlertEvent(
                    status.getCategory(),
                    status.getCurrentSpend(),
                    status.getMonthlyLimit(),
                    status.getWarningAmount(),
                    status.getStatus(),
                    msg
            );

            log.warn("Triggering Budget Alert: {}", msg);

            // Publish to budget-alerts topic
            kafkaTemplate.send(KafkaTopicConfig.BUDGET_ALERTS_TOPIC, status.getCategory(), alertEvent);
        }
    }
}