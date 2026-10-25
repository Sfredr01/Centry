package com.centry.service;

import com.centry.entity.ExpenseEntity;
import com.centry.entity.SubscriptionAlertEntity;
import com.centry.model.SubscriptionAlertEvent;
import com.centry.dto.ExpenseEvent;
import com.centry.repository.ExpenseRepository;
import com.centry.repository.SubscriptionAlertRepository;
import com.centry.repository.UserRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

@Service
public class ExpenseService {

    private static final Logger log = LoggerFactory.getLogger(ExpenseService.class);

    private final BudgetAlertPolicy budgetAlertPolicy;
    private final SubscriptionTrackingService subscriptionTrackingService;
    private final ApplicationEventPublisher events;
    private final ExpenseRepository expenseRepository;
    private final SubscriptionAlertRepository subscriptionAlertRepository;
    private final UserRepository users;

    private final Clock clock;

    private final Counter expensesConsumedCounter;
    private final Counter budgetAlertsCounter;
    private final Counter subscriptionAlertsCounter;
    private final Timer expenseProcessingTimer;

    public ExpenseService(BudgetAlertPolicy budgetAlertPolicy,
                           SubscriptionTrackingService subscriptionTrackingService,
                           ApplicationEventPublisher events,
                           ExpenseRepository expenseRepository,
                           SubscriptionAlertRepository subscriptionAlertRepository,
                           UserRepository users,
                           MeterRegistry meterRegistry,
                           Clock clock) {
        this.budgetAlertPolicy = budgetAlertPolicy;
        this.subscriptionTrackingService = subscriptionTrackingService;
        this.events = events;
        this.expenseRepository = expenseRepository;
        this.subscriptionAlertRepository = subscriptionAlertRepository;
        this.users = users;
        this.clock = clock;

        this.expensesConsumedCounter = meterRegistry.counter("centry.expenses.consumed");
        this.budgetAlertsCounter = meterRegistry.counter("centry.budget.alerts.emitted");
        this.subscriptionAlertsCounter = meterRegistry.counter("centry.subscription.alerts.emitted");
        this.expenseProcessingTimer = meterRegistry.timer("centry.expense.processing.duration");
    }

    /** Outcome of {@link #record}: the stored expense, and whether this call created it. */
    public record Recorded(ExpenseEntity expense, boolean created) {
    }

    /**
     * Single use case: persist, budget check, subscription check, alerts, metrics.
     *
     * <p>{@code transactionId} is the idempotency key: replaying an identical expense returns the stored one
     * untouched, replaying it with a different payload is a conflict. The expense and its alert rows commit
     * or roll back together, and alert events only reach their listeners after the commit.
     *
     * @param createdBy the logged-in user recording the expense, or null (Kafka). It is an argument and never
     *                  part of the event, so no client can claim to be someone else. A missing
     *                  {@code spentBy} defaults to it.
     * @throws UnknownSpenderException if {@code spentBy} is not a user
     */
    @Transactional
    public Recorded record(ExpenseEvent event, Long createdBy) {
        return expenseProcessingTimer.record(() -> {
            LocalDateTime txTime = event.getTimestamp() != null ? event.getTimestamp() : LocalDateTime.now(clock);
            // Every entry point (HTTP, Kafka) may omit the id; without one the expense cannot be replayed safely.
            if (event.getTransactionId() == null) {
                event.setTransactionId(UUID.randomUUID().toString());
            }

            Optional<ExpenseEntity> existing = expenseRepository.findByTransactionId(event.getTransactionId());
            if (existing.isPresent()) {
                return replay(existing.get(), event);
            }

            // createdBy comes from the authenticated principal, so only a client-supplied spender is checked.
            if (event.getSpentBy() != null && !users.existsById(event.getSpentBy())) {
                throw new UnknownSpenderException(event.getSpentBy());
            }
            Long spentBy = event.getSpentBy() != null ? event.getSpentBy() : createdBy;

            // 1. Persist Expense Entity
            ExpenseEntity saved;
            try {
                saved = expenseRepository.save(new ExpenseEntity(null, event.getTransactionId(),
                        event.getMerchant(), event.getAmount(), event.getCategory(), txTime, spentBy, createdBy));
            } catch (DuplicateKeyException race) {
                // A concurrent request stored the same key between the lookup and the insert.
                return replay(expenseRepository.findByTransactionId(event.getTransactionId())
                        .orElseThrow(() -> race), event);
            }
            expensesConsumedCounter.increment();
            log.info("Recorded expense: merchant='{}', amount=${}, category='{}'",
                    event.getMerchant(), event.getAmount(), event.getCategory());

            // 2. Budget check: derived from the stored expenses, deduplicated per category, month and level.
            checkBudget(event.getCategory(), txTime);

            // 3. Subscription price check against the merchant's previous charge, read from the database.
            subscriptionTrackingService.checkPriceIncrease(saved).ifPresent(subscriptionAlert -> {
                subscriptionAlertsCounter.increment();
                log.warn("Triggering Subscription Alert: {}", subscriptionAlert.getMessage());

                subscriptionAlertRepository.save(new SubscriptionAlertEntity(null, subscriptionAlert.getMerchant(),
                        subscriptionAlert.getPreviousAmount(), subscriptionAlert.getCurrentAmount(),
                        subscriptionAlert.getAnomalyType(), subscriptionAlert.getMessage(),
                        subscriptionAlert.getTimestamp()));

                events.publishEvent(subscriptionAlert);
            });

            return new Recorded(saved, true);
        });
    }

    /**
     * Rewrites the editable fields of a stored expense: merchant, amount, category, date and spender. The
     * transaction id and the creator stay. The budget policy runs for the (possibly new) category and month, so
     * a threshold the edit newly crosses alerts once; alerts already sent are neither removed nor repeated, and
     * lowering spend never retracts one.
     *
     * @param event carries the new values; its timestamp must be set and its {@code spentBy} is taken as is
     *              (null means unattributed, unlike {@link #record} where it defaults to the creator)
     * @throws UnknownSpenderException if {@code spentBy} is not a user
     */
    @Transactional
    public ExpenseEntity update(ExpenseEntity existing, ExpenseEvent event) {
        if (event.getSpentBy() != null && !users.existsById(event.getSpentBy())) {
            throw new UnknownSpenderException(event.getSpentBy());
        }
        ExpenseEntity updated = new ExpenseEntity(existing.id(), existing.transactionId(), event.getMerchant(),
                event.getAmount(), event.getCategory(), event.getTimestamp(), event.getSpentBy(),
                existing.createdBy());
        expenseRepository.update(updated);
        log.info("Updated expense {}: merchant='{}', amount=${}, category='{}'", existing.id(),
                event.getMerchant(), event.getAmount(), event.getCategory());
        checkBudget(event.getCategory(), event.getTimestamp());
        return updated;
    }

    private void checkBudget(String category, LocalDateTime occurredAt) {
        budgetAlertPolicy.evaluate(category, occurredAt).ifPresent(alert -> {
            budgetAlertsCounter.increment();
            events.publishEvent(alert);
        });
    }

    private Recorded replay(ExpenseEntity stored, ExpenseEvent event) {
        boolean sameExpense = Objects.equals(stored.merchant(), event.getMerchant())
                && Objects.equals(stored.category(), event.getCategory())
                && stored.amount().compareTo(event.getAmount()) == 0
                // A replay that never supplied a timestamp can not contradict the stored one.
                && (event.getTimestamp() == null || event.getTimestamp().equals(stored.timestamp()))
                // Same for the spender: an omitted one defaults to the caller and is not compared.
                && (event.getSpentBy() == null || event.getSpentBy().equals(stored.spentBy()));
        if (!sameExpense) {
            throw new ExpenseConflictException();
        }
        log.info("Ignoring replay of transaction '{}'", stored.transactionId());
        return new Recorded(stored, false);
    }
}
