package com.centry.service;

import com.centry.entity.BudgetAlertEntity;
import com.centry.model.BudgetAlertEvent;
import com.centry.model.BudgetLevel;
import com.centry.model.BudgetStatus;
import com.centry.model.Keys;
import com.centry.repository.BudgetAlertRepository;
import com.centry.repository.BudgetStatusQuery;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.Locale;
import java.util.Optional;

/**
 * Decides whether a just-recorded expense raises a budget alert. Runs inside the record transaction, after
 * the expense insert, so the spend already includes the expense.
 *
 * <p>Only the current level of the expense's own month is evaluated. The alert row is the dedupe record: its
 * unique index lets at most one WARNING and one LIMIT through per category and month, and an event exists
 * only for a row that was actually inserted.
 */
@Service
public class BudgetAlertPolicy {

    private static final Logger log = LoggerFactory.getLogger(BudgetAlertPolicy.class);

    private final BudgetStatusQuery statusQuery;
    private final BudgetAlertRepository alertRepository;
    private final Clock clock;

    public BudgetAlertPolicy(BudgetStatusQuery statusQuery, BudgetAlertRepository alertRepository, Clock clock) {
        this.statusQuery = statusQuery;
        this.alertRepository = alertRepository;
        this.clock = clock;
    }

    public Optional<BudgetAlertEvent> evaluate(String category, LocalDateTime occurredAt) {
        YearMonth period = YearMonth.from(occurredAt);
        return statusQuery.status(Keys.of(category), period)
                .filter(status -> status.level() != BudgetLevel.OK)
                .flatMap(status -> raise(status, period));
    }

    private Optional<BudgetAlertEvent> raise(BudgetStatus status, YearMonth period) {
        boolean limit = status.level() == BudgetLevel.LIMIT_EXCEEDED;
        String message = String.format(Locale.ROOT,
                "Budget alert for [%s]: Current spend $%.2f exceeds %s of $%.2f",
                status.category(), status.spent(), limit ? "monthly limit" : "warning threshold",
                limit ? status.monthlyLimit() : status.warningAmount());

        boolean inserted = alertRepository.insertIfAbsent(new BudgetAlertEntity(null, status.category(),
                status.spent(), status.monthlyLimit(), status.warningAmount(), status.level(), message,
                LocalDateTime.now(clock)), period);
        if (!inserted) {
            return Optional.empty();
        }
        log.warn("Triggering Budget Alert: {}", message);
        return Optional.of(new BudgetAlertEvent(status.category(), status.spent(), status.monthlyLimit(),
                status.warningAmount(), status.level(), message));
    }
}
