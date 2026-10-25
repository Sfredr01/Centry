package com.centry.notify;

import com.centry.entity.BudgetAlertEntity;
import com.centry.model.BudgetAlertEvent;
import com.centry.repository.BudgetAlertRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Re-sends alerts whose delivery failed ({@code notified_at} still NULL).
 *
 * <p>An alert is retried while it is between one minute and 24 hours old. The one-minute grace keeps the
 * sweep from racing the asynchronous dispatch that follows the commit; older alerts are abandoned.
 * Legacy rows without a period never carry a delivery state and are ignored.
 */
@Component
public class AlertRetrySweeper {

    private static final Logger log = LoggerFactory.getLogger(AlertRetrySweeper.class);
    private static final Duration GRACE = Duration.ofMinutes(1);
    private static final Duration GIVE_UP_AFTER = Duration.ofHours(24);

    private final BudgetAlertRepository alerts;
    private final AlertDispatcher dispatcher;
    private final Clock clock;

    // An abandoned alert stays NULL forever; remember which ones were logged so each is reported once.
    private final Set<Long> abandonedLogged = ConcurrentHashMap.newKeySet();

    public AlertRetrySweeper(BudgetAlertRepository alerts, AlertDispatcher dispatcher, Clock clock) {
        this.alerts = alerts;
        this.dispatcher = dispatcher;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${centry.notify.retry-interval:PT5M}")
    public void sweep() {
        LocalDateTime now = LocalDateTime.now(clock);
        LocalDateTime oldest = now.minus(GIVE_UP_AFTER);

        for (Long id : alerts.findAbandonedIds(oldest)) {
            if (abandonedLogged.add(id)) {
                log.warn("Abandoning alert {}: still undelivered after {} hours", id, GIVE_UP_AFTER.toHours());
            }
        }
        for (BudgetAlertEntity alert : alerts.findUnsent(oldest, now.minus(GRACE))) {
            log.info("Retrying delivery of alert {} on category [{}]", alert.id(), alert.category());
            dispatcher.dispatch(toEvent(alert));
        }
    }

    private static BudgetAlertEvent toEvent(BudgetAlertEntity alert) {
        BudgetAlertEvent event = new BudgetAlertEvent(alert.category(), alert.currentSpend(), alert.monthlyLimit(),
                alert.warningAmount(), alert.status(), alert.message());
        event.setAlertId(alert.id());
        return event;
    }
}
