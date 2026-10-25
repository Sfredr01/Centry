package com.centry.service;

import com.centry.config.KafkaTopicConfig;
import com.centry.entity.BudgetAlertEntity;
import com.centry.entity.ExpenseEntity;
import com.centry.entity.SubscriptionAlertEntity;
import com.centry.model.BudgetAlertEvent;
import com.centry.model.BudgetStatus;
import com.centry.model.SubscriptionAlertEvent;
import com.centry.dto.ExpenseEvent;
import com.centry.repository.BudgetAlertRepository;
import com.centry.repository.ExpenseRepository;
import com.centry.repository.SubscriptionAlertRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

@Service
public class ExpenseConsumer {

    private static final Logger log = LoggerFactory.getLogger(ExpenseConsumer.class);

    private final BudgetTrackingService budgetTrackingService;
    private final SubscriptionTrackingService subscriptionTrackingService;
    private final KafkaTemplate<String, Object> kafkaTemplate;
    private final ExpenseRepository expenseRepository;
    private final BudgetAlertRepository budgetAlertRepository;
    private final SubscriptionAlertRepository subscriptionAlertRepository;

    private final Counter expensesConsumedCounter;
    private final Counter budgetAlertsCounter;
    private final Counter subscriptionAlertsCounter;
    private final Timer expenseProcessingTimer;

    public ExpenseConsumer(BudgetTrackingService budgetTrackingService,
                           SubscriptionTrackingService subscriptionTrackingService,
                           KafkaTemplate<String, Object> kafkaTemplate,
                           ExpenseRepository expenseRepository,
                           BudgetAlertRepository budgetAlertRepository,
                           SubscriptionAlertRepository subscriptionAlertRepository,
                           MeterRegistry meterRegistry) {
        this.budgetTrackingService = budgetTrackingService;
        this.subscriptionTrackingService = subscriptionTrackingService;
        this.kafkaTemplate = kafkaTemplate;
        this.expenseRepository = expenseRepository;
        this.budgetAlertRepository = budgetAlertRepository;
        this.subscriptionAlertRepository = subscriptionAlertRepository;

        this.expensesConsumedCounter = meterRegistry.counter("centry.expenses.consumed");
        this.budgetAlertsCounter = meterRegistry.counter("centry.budget.alerts.emitted");
        this.subscriptionAlertsCounter = meterRegistry.counter("centry.subscription.alerts.emitted");
        this.expenseProcessingTimer = meterRegistry.timer("centry.expense.processing.duration");
    }

    @KafkaListener(topics = "raw-expenses", groupId = "centry-group")
    public void consumeRawExpense(ExpenseEvent event) {
        expenseProcessingTimer.record(() -> {
            expensesConsumedCounter.increment();
            log.info("Successfully received event from Kafka [raw-expenses]: merchant='{}', amount=${}, category='{}'",
                    event.getMerchant(), event.getAmount(), event.getCategory());

            // 1. Persist Expense Entity
            LocalDateTime txTime = event.getTimestamp() != null ? event.getTimestamp() : LocalDateTime.now();
            ExpenseEntity expenseEntity = ExpenseEntity.builder()
                    .transactionId(event.getTransactionId())
                    .merchant(event.getMerchant())
                    .amount(event.getAmount())
                    .category(event.getCategory())
                    .timestamp(txTime)
                    .build();
            expenseRepository.save(expenseEntity);

            // 2. Update in-memory budget state
            BudgetStatus status = budgetTrackingService.recordExpense(event.getCategory(), event.getAmount());

            if (status != null && status.getStatus() != BudgetStatus.Status.OK) {
                budgetAlertsCounter.increment();
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

                // Persist budget alert
                budgetAlertRepository.save(BudgetAlertEntity.builder()
                        .category(status.getCategory())
                        .currentSpend(status.getCurrentSpend())
                        .monthlyLimit(status.getMonthlyLimit())
                        .warningAmount(status.getWarningAmount())
                        .status(status.getStatus())
                        .message(msg)
                        .timestamp(LocalDateTime.now())
                        .build());

                // Publish to budget-alerts topic
                kafkaTemplate.send(KafkaTopicConfig.BUDGET_ALERTS_TOPIC, status.getCategory(), alertEvent);
            }

            // 3. Process subscription and anomaly detection
            SubscriptionAlertEvent subscriptionAlert = subscriptionTrackingService.processAndCheckSubscription(
                    event.getMerchant(), event.getAmount(), txTime);

            if (subscriptionAlert != null) {
                subscriptionAlertsCounter.increment();
                log.warn("Triggering Subscription Alert: {}", subscriptionAlert.getMessage());

                // Persist subscription alert
                subscriptionAlertRepository.save(SubscriptionAlertEntity.builder()
                        .merchant(subscriptionAlert.getMerchant())
                        .previousAmount(subscriptionAlert.getPreviousAmount())
                        .currentAmount(subscriptionAlert.getCurrentAmount())
                        .anomalyType(subscriptionAlert.getAnomalyType())
                        .message(subscriptionAlert.getMessage())
                        .timestamp(subscriptionAlert.getTimestamp() != null ? subscriptionAlert.getTimestamp() : LocalDateTime.now())
                        .build());

                kafkaTemplate.send(KafkaTopicConfig.SUBSCRIPTION_ALERTS_TOPIC, subscriptionAlert.getMerchant(), subscriptionAlert);
            }
        });
    }
}
