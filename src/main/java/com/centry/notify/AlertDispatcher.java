package com.centry.notify;

import com.centry.model.BudgetAlertEvent;
import com.centry.model.BudgetLevel;
import com.centry.repository.BudgetAlertRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Sends a budget alert through every configured notifier and records the outcome in {@code notified_at}.
 *
 * <p>The alert counts as delivered only when all notifiers succeed; otherwise {@code notified_at} stays NULL
 * and {@link AlertRetrySweeper} tries again. Known simplification: delivery is tracked per alert, not per
 * notifier, so after a partial failure the retry re-sends to every notifier and one of them may see a
 * duplicate. With no notifier configured nothing can ever be sent, so the alert is marked delivered at once.
 */
@Service
public class AlertDispatcher {

    private static final Logger log = LoggerFactory.getLogger(AlertDispatcher.class);

    private final List<Notifier> notifiers;
    private final BudgetAlertRepository alerts;
    private final Clock clock;

    // ObjectProvider, not List: with no notifier configured there is no bean to inject, and that is valid.
    public AlertDispatcher(ObjectProvider<Notifier> notifiers, BudgetAlertRepository alerts, Clock clock) {
        this.notifiers = notifiers.orderedStream().toList();
        this.alerts = alerts;
        this.clock = clock;
    }

    // Async keeps the outbound calls off the request thread; AFTER_COMMIT means a rolled-back
    // expense never notifies anyone.
    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onBudgetAlert(BudgetAlertEvent alert) {
        dispatch(alert);
    }

    /** Synchronous delivery, shared with the retry sweeper. Never throws for a failing notifier. */
    public boolean dispatch(BudgetAlertEvent alert) {
        if (alert.getAlertLevel() == BudgetLevel.LIMIT_EXCEEDED) {
            log.error("🚨 CRITICAL BUDGET ALERT: Category [{}] - Total spend: ${} (Limit: ${}). Message: {}",
                    alert.getCategory(), alert.getCurrentSpend(), alert.getMonthlyLimit(), alert.getMessage());
        } else {
            log.warn("⚠️ WARNING BUDGET ALERT: Category [{}] - Total spend: ${} (Threshold: ${}). Message: {}",
                    alert.getCategory(), alert.getCurrentSpend(), alert.getWarningThreshold(), alert.getMessage());
        }

        boolean delivered = true;
        for (Notifier notifier : notifiers) {
            try {
                notifier.send(alert);
            } catch (RuntimeException e) {
                delivered = false;
                log.error("{} failed for alert {} on category [{}]: {}", notifier.getClass().getSimpleName(),
                        alert.getAlertId(), alert.getCategory(), e.getMessage());
            }
        }
        if (delivered) {
            alerts.markNotified(alert.getAlertId(), LocalDateTime.now(clock));
        }
        return delivered;
    }
}
