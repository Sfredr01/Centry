package com.centry.service;

import com.centry.dto.ExpenseEvent;
import com.centry.entity.BudgetAlertEntity;
import com.centry.entity.ExpenseEntity;
import com.centry.entity.SubscriptionAlertEntity;
import com.centry.model.BudgetAlertEvent;
import com.centry.model.BudgetStatus;
import com.centry.model.SubscriptionAlertEvent;
import com.centry.repository.BudgetAlertRepository;
import com.centry.repository.ExpenseRepository;
import com.centry.repository.SubscriptionAlertRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DuplicateKeyException;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ExpenseServiceTest {

    private static final LocalDateTime TX_TIME = LocalDateTime.of(2025, 3, 4, 5, 6, 7);
    private static final LocalDateTime CLOCK_NOW = LocalDateTime.of(2025, 11, 15, 10, 0, 0);
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2025-11-15T10:00:00Z"), ZoneOffset.UTC);

    @Mock private BudgetTrackingService budgetTrackingService;
    @Mock private SubscriptionTrackingService subscriptionTrackingService;
    @Mock private ApplicationEventPublisher events;
    @Mock private ExpenseRepository expenseRepository;
    @Mock private BudgetAlertRepository budgetAlertRepository;
    @Mock private SubscriptionAlertRepository subscriptionAlertRepository;

    private SimpleMeterRegistry registry;
    private ExpenseService service;

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        service = new ExpenseService(budgetTrackingService, subscriptionTrackingService, events,
                expenseRepository, budgetAlertRepository, subscriptionAlertRepository, registry, CLOCK);
    }

    private ExpenseEvent event(LocalDateTime ts) {
        return ExpenseEvent.builder()
                .transactionId("tx-1").merchant("Netflix").amount(new BigDecimal("15.99"))
                .category("Entertainment").timestamp(ts).build();
    }

    private double count(String name) {
        return registry.counter(name).count();
    }

    private ExpenseEntity savedExpense() {
        ArgumentCaptor<ExpenseEntity> c = ArgumentCaptor.forClass(ExpenseEntity.class);
        verify(expenseRepository).save(c.capture());
        return c.getValue();
    }

    private BudgetStatus status(BudgetStatus.Status s) {
        return new BudgetStatus("Entertainment", new BigDecimal("120.00"), new BigDecimal("100.00"),
                new BigDecimal("80.00"), s);
    }

    @Test
    void persistsExpenseWithEventTimestampAndSendsNoAlertsWhenNothingTriggers() {
        service.record(event(TX_TIME));

        ExpenseEntity e = savedExpense();
        assertThat(e.transactionId()).isEqualTo("tx-1");
        assertThat(e.merchant()).isEqualTo("Netflix");
        assertThat(e.amount()).isEqualByComparingTo("15.99");
        assertThat(e.category()).isEqualTo("Entertainment");
        assertThat(e.timestamp()).isEqualTo(TX_TIME);
        verify(subscriptionTrackingService).processAndCheckSubscription("Netflix", new BigDecimal("15.99"), TX_TIME);
        verifyNoInteractions(budgetAlertRepository, subscriptionAlertRepository, events);
        assertThat(count("centry.expenses.consumed")).isEqualTo(1.0);
        assertThat(count("centry.budget.alerts.emitted")).isZero();
        assertThat(count("centry.subscription.alerts.emitted")).isZero();
        assertThat(registry.timer("centry.expense.processing.duration").count()).isEqualTo(1L);
    }

    @Test
    void returnsTheSavedExpenseAsCreated() {
        ExpenseEntity saved = new ExpenseEntity(42L, "tx-1", "Netflix", new BigDecimal("15.99"),
                "Entertainment", TX_TIME);
        when(expenseRepository.save(any())).thenReturn(saved);

        ExpenseService.Recorded result = service.record(event(TX_TIME));

        assertThat(result.expense()).isSameAs(saved);
        assertThat(result.created()).isTrue();
    }

    private ExpenseEntity existing(LocalDateTime ts) {
        return new ExpenseEntity(9L, "tx-1", "Netflix", new BigDecimal("15.990"), "Entertainment", ts);
    }

    private void assertNoSideEffects() {
        verify(expenseRepository, never()).save(any());
        verifyNoInteractions(budgetTrackingService, subscriptionTrackingService, budgetAlertRepository,
                subscriptionAlertRepository, events);
        assertThat(count("centry.expenses.consumed")).isZero();
    }

    @Test
    void duplicateTransactionIdReturnsExistingWithoutSideEffects() {
        ExpenseEntity stored = existing(TX_TIME);
        when(expenseRepository.findByTransactionId("tx-1")).thenReturn(Optional.of(stored));

        ExpenseService.Recorded result = service.record(event(TX_TIME));

        assertThat(result.expense()).isSameAs(stored);
        assertThat(result.created()).isFalse();
        assertNoSideEffects();
    }

    // A replay that never supplied a timestamp can not match the one the first call defaulted.
    @Test
    void replayWithoutTimestampIgnoresTheStoredOne() {
        ExpenseEntity stored = existing(TX_TIME);
        when(expenseRepository.findByTransactionId("tx-1")).thenReturn(Optional.of(stored));

        ExpenseService.Recorded result = service.record(event(null));

        assertThat(result.created()).isFalse();
        assertNoSideEffects();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("conflictingPayloads")
    void conflictingReplayThrows(String name, ExpenseEvent changed) {
        when(expenseRepository.findByTransactionId("tx-1")).thenReturn(Optional.of(existing(TX_TIME)));

        assertThatThrownBy(() -> service.record(changed))
                .isInstanceOf(ExpenseConflictException.class)
                .hasMessage("transactionId already used for a different expense");
        assertNoSideEffects();
    }

    private static ExpenseEvent.ExpenseEventBuilder sameAsStored() {
        return ExpenseEvent.builder().transactionId("tx-1").merchant("Netflix")
                .amount(new BigDecimal("15.99")).category("Entertainment").timestamp(TX_TIME);
    }

    static Stream<Arguments> conflictingPayloads() {
        return Stream.of(
                Arguments.of("amount", sameAsStored().amount(new BigDecimal("16.00")).build()),
                Arguments.of("merchant", sameAsStored().merchant("Hulu").build()),
                Arguments.of("category", sameAsStored().category("Food").build()),
                Arguments.of("timestamp", sameAsStored().timestamp(TX_TIME.plusDays(1)).build()));
    }

    @Test
    void duplicateKeyRaceRereadsExisting() {
        ExpenseEntity stored = existing(TX_TIME);
        when(expenseRepository.findByTransactionId("tx-1")).thenReturn(Optional.empty(), Optional.of(stored));
        when(expenseRepository.save(any())).thenThrow(new DuplicateKeyException("race"));

        ExpenseService.Recorded result = service.record(event(TX_TIME));

        assertThat(result.expense()).isSameAs(stored);
        assertThat(result.created()).isFalse();
        verifyNoInteractions(budgetTrackingService, subscriptionTrackingService, budgetAlertRepository,
                subscriptionAlertRepository, events);
    }

    @Test
    void duplicateKeyRaceWithConflictingPayloadThrows() {
        when(expenseRepository.findByTransactionId("tx-1"))
                .thenReturn(Optional.empty(), Optional.of(existing(TX_TIME.plusDays(1))));
        when(expenseRepository.save(any())).thenThrow(new DuplicateKeyException("race"));

        assertThatThrownBy(() -> service.record(event(TX_TIME))).isInstanceOf(ExpenseConflictException.class);
    }

    @Test
    void duplicateKeyWithNothingToRereadPropagates() {
        DuplicateKeyException failure = new DuplicateKeyException("not our key");
        when(expenseRepository.findByTransactionId("tx-1")).thenReturn(Optional.empty());
        when(expenseRepository.save(any())).thenThrow(failure);

        assertThatThrownBy(() -> service.record(event(TX_TIME))).isSameAs(failure);
    }

    @Test
    void nullTimestampDefaultsFromClock() {
        service.record(event(null));

        ExpenseEntity e = savedExpense();
        assertThat(e.timestamp()).isEqualTo(CLOCK_NOW);
        verify(subscriptionTrackingService).processAndCheckSubscription("Netflix", new BigDecimal("15.99"), CLOCK_NOW);
        verify(budgetTrackingService).recordExpense("Entertainment", new BigDecimal("15.99"), CLOCK_NOW);
    }

    @Test
    void monthDerivedFromOccurredOnNotClock() {
        LocalDateTime october = LocalDateTime.of(2025, 10, 20, 12, 0);

        service.record(event(october));

        verify(budgetTrackingService).recordExpense("Entertainment", new BigDecimal("15.99"), october);
    }

    @Test
    void okBudgetStatusEmitsNoBudgetAlert() {
        when(budgetTrackingService.recordExpense("Entertainment", new BigDecimal("15.99"), TX_TIME))
                .thenReturn(status(BudgetStatus.Status.OK));

        service.record(event(TX_TIME));

        verifyNoInteractions(budgetAlertRepository, events);
        assertThat(count("centry.budget.alerts.emitted")).isZero();
    }

    @Test
    void warningExceededPersistsAndPublishesAlertMentioningWarningThreshold() {
        when(budgetTrackingService.recordExpense("Entertainment", new BigDecimal("15.99"), TX_TIME))
                .thenReturn(status(BudgetStatus.Status.WARNING_EXCEEDED));
        service.record(event(TX_TIME));

        String expectedMsg = "Budget alert for [Entertainment]: Current spend $120.00 exceeds warning threshold of $80.00";
        ArgumentCaptor<BudgetAlertEntity> saved = ArgumentCaptor.forClass(BudgetAlertEntity.class);
        verify(budgetAlertRepository).save(saved.capture());
        BudgetAlertEntity a = saved.getValue();
        assertThat(a.category()).isEqualTo("Entertainment");
        assertThat(a.currentSpend()).isEqualByComparingTo("120.00");
        assertThat(a.monthlyLimit()).isEqualByComparingTo("100.00");
        assertThat(a.warningAmount()).isEqualByComparingTo("80.00");
        assertThat(a.status()).isEqualTo(BudgetStatus.Status.WARNING_EXCEEDED);
        assertThat(a.message()).isEqualTo(expectedMsg);
        assertThat(a.timestamp()).isEqualTo(CLOCK_NOW);

        ArgumentCaptor<Object> payload = ArgumentCaptor.forClass(Object.class);
        verify(events).publishEvent(payload.capture());
        BudgetAlertEvent ev = (BudgetAlertEvent) payload.getValue();
        assertThat(ev.getMessage()).isEqualTo(expectedMsg);
        assertThat(ev.getAlertLevel()).isEqualTo(BudgetStatus.Status.WARNING_EXCEEDED);
        assertThat(ev.getWarningThreshold()).isEqualByComparingTo("80.00");
        assertThat(ev.getMonthlyLimit()).isEqualByComparingTo("100.00");
        assertThat(count("centry.budget.alerts.emitted")).isEqualTo(1.0);
    }

    @Test
    void limitExceededAlertMentionsMonthlyLimit() {
        when(budgetTrackingService.recordExpense("Entertainment", new BigDecimal("15.99"), TX_TIME))
                .thenReturn(status(BudgetStatus.Status.LIMIT_EXCEEDED));

        service.record(event(TX_TIME));

        String expectedMsg = "Budget alert for [Entertainment]: Current spend $120.00 exceeds monthly limit of $100.00";
        ArgumentCaptor<BudgetAlertEntity> saved = ArgumentCaptor.forClass(BudgetAlertEntity.class);
        verify(budgetAlertRepository).save(saved.capture());
        assertThat(saved.getValue().message()).isEqualTo(expectedMsg);
        assertThat(saved.getValue().status()).isEqualTo(BudgetStatus.Status.LIMIT_EXCEEDED);
        ArgumentCaptor<Object> payload = ArgumentCaptor.forClass(Object.class);
        verify(events).publishEvent(payload.capture());
        assertThat(((BudgetAlertEvent) payload.getValue()).getAlertLevel())
                .isEqualTo(BudgetStatus.Status.LIMIT_EXCEEDED);
        assertThat(count("centry.budget.alerts.emitted")).isEqualTo(1.0);
    }

    private SubscriptionAlertEvent subAlert(LocalDateTime ts) {
        return SubscriptionAlertEvent.builder()
                .merchant("Netflix").previousAmount(new BigDecimal("9.99")).currentAmount(new BigDecimal("15.99"))
                .anomalyType("PRICE_INCREASE").message("Price went up").timestamp(ts).build();
    }

    @Test
    void subscriptionAlertWithTimestampIsPersistedAndPublished() {
        SubscriptionAlertEvent alert = subAlert(TX_TIME);
        when(subscriptionTrackingService.processAndCheckSubscription("Netflix", new BigDecimal("15.99"), TX_TIME))
                .thenReturn(alert);

        service.record(event(TX_TIME));

        ArgumentCaptor<SubscriptionAlertEntity> saved = ArgumentCaptor.forClass(SubscriptionAlertEntity.class);
        verify(subscriptionAlertRepository).save(saved.capture());
        SubscriptionAlertEntity s = saved.getValue();
        assertThat(s.merchant()).isEqualTo("Netflix");
        assertThat(s.previousAmount()).isEqualByComparingTo("9.99");
        assertThat(s.currentAmount()).isEqualByComparingTo("15.99");
        assertThat(s.anomalyType()).isEqualTo("PRICE_INCREASE");
        assertThat(s.message()).isEqualTo("Price went up");
        assertThat(s.timestamp()).isEqualTo(TX_TIME);
        verify(events).publishEvent((Object) alert);
        verify(budgetAlertRepository, never()).save(any());
        assertThat(count("centry.subscription.alerts.emitted")).isEqualTo(1.0);
    }

    @Test
    void subscriptionAlertWithoutTimestampGetsCurrentTime() {
        SubscriptionAlertEvent alert = subAlert(null);
        when(subscriptionTrackingService.processAndCheckSubscription("Netflix", new BigDecimal("15.99"), TX_TIME))
                .thenReturn(alert);
        service.record(event(TX_TIME));

        ArgumentCaptor<SubscriptionAlertEntity> saved = ArgumentCaptor.forClass(SubscriptionAlertEntity.class);
        verify(subscriptionAlertRepository).save(saved.capture());
        assertThat(saved.getValue().timestamp()).isEqualTo(CLOCK_NOW);
        verify(events).publishEvent((Object) alert);
        assertThat(count("centry.subscription.alerts.emitted")).isEqualTo(1.0);
    }
}
