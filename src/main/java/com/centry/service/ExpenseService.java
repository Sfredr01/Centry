package com.centry.service;

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
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.LocalDateTime;

@Service
public class ExpenseService {

    private static final Logger log = LoggerFactory.getLogger(ExpenseService.class);

    private final BudgetTrackingService budgetTrackingService;
    private final SubscriptionTrackingService subscriptionTrackingService;
    private final ApplicationEventPublisher events;
    private final ExpenseRepository expenseRepository;
    private final BudgetAlertRepository budgetAlertRepository;
    private final SubscriptionAlertRepository subscriptionAlertRepository;

    private final Clock clock;

    private final Counter expensesConsumedCounter;
    private final Counter budgetAlertsCounter;
    private final Counter subscriptionAlertsCounter;
    private final Timer expenseProcessingTimer;

    public ExpenseService(BudgetTrackingService budgetTrackingService,
                           SubscriptionTrackingService subscriptionTrackingService,
                           ApplicationEventPublisher events,
                           ExpenseRepository expenseRepository,
                           BudgetAlertRepository budgetAlertRepository,
                           SubscriptionAlertRepository subscriptionAlertRepository,
                           MeterRegistry meterRegistry,
                           Clock clock) {
        this.budgetTrackingService = budgetTrackingService;
        this.subscriptionTrackingService = subscriptionTrackingService;
        this.events = events;
        this.expenseRepository = expenseRepository;
        this.budgetAlertRepository = budgetAlertRepository;
        this.subscriptionAlertRepository = subscriptionAlertRepository;
        this.clock = clock;

        this.expensesConsumedCounter = meterRegistry.counter("centry.expenses.consumed");
        this.budgetAlertsCounter = meterRegistry.counter("centry.budget.alerts.emitted");
        this.subscriptionAlertsCounter = meterRegistry.counter("centry.subscription.alerts.emitted");
        this.expenseProcessingTimer = meterRegistry.timer("centry.expense.processing.duration");
    }

    /** Outcome of {@link #record}: the stored expense, and whether this call created it. */
    public record Recorded(ExpenseEntity expense, boolean created) {
    }

    /** Single use case: persist, budget check, subscription check, alerts, metrics. */
    public Recorded record(ExpenseEvent event) {
        return expenseProcessingTimer.record(() -> {
            expensesConsumedCounter.increment();
            log.info("Recording expense: merchant='{}', amount=${}, category='{}'",
                    event.getMerchant(), event.getAmount(), event.getCategory());

            // 1. Persist Expense Entity
            LocalDateTime txTime = event.getTimestamp() != null ? event.getTimestamp() : LocalDateTime.now(clock);
            ExpenseEntity saved = expenseRepository.save(new ExpenseEntity(null, event.getTransactionId(), event.getMerchant(),
                    event.getAmount(), event.getCategory(), txTime));

            // 2. Update in-memory budget state
            BudgetStatus status = budgetTrackingService.recordExpense(event.getCategory(), event.getAmount(), txTime);

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
                budgetAlertRepository.save(new BudgetAlertEntity(null, status.getCategory(),
                        status.getCurrentSpend(), status.getMonthlyLimit(), status.getWarningAmount(),
                        status.getStatus(), msg, LocalDateTime.now(clock)));

                events.publishEvent(alertEvent);
            }

            // 3. Process subscription and anomaly detection
            SubscriptionAlertEvent subscriptionAlert = subscriptionTrackingService.processAndCheckSubscription(
                    event.getMerchant(), event.getAmount(), txTime);

            if (subscriptionAlert != null) {
                subscriptionAlertsCounter.increment();
                log.warn("Triggering Subscription Alert: {}", subscriptionAlert.getMessage());

                // Persist subscription alert
                subscriptionAlertRepository.save(new SubscriptionAlertEntity(null, subscriptionAlert.getMerchant(),
                        subscriptionAlert.getPreviousAmount(), subscriptionAlert.getCurrentAmount(),
                        subscriptionAlert.getAnomalyType(), subscriptionAlert.getMessage(),
                        subscriptionAlert.getTimestamp() != null ? subscriptionAlert.getTimestamp() : LocalDateTime.now(clock)));

                events.publishEvent(subscriptionAlert);
            }

            return new Recorded(saved, true);
        });
    }
}
