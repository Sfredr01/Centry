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
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ExpenseServiceTest {

    private static final LocalDateTime TX_TIME = LocalDateTime.of(2025, 3, 4, 5, 6, 7);

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
                expenseRepository, budgetAlertRepository, subscriptionAlertRepository, registry);
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
        assertThat(e.getTransactionId()).isEqualTo("tx-1");
        assertThat(e.getMerchant()).isEqualTo("Netflix");
        assertThat(e.getAmount()).isEqualByComparingTo("15.99");
        assertThat(e.getCategory()).isEqualTo("Entertainment");
        assertThat(e.getTimestamp()).isEqualTo(TX_TIME);
        verify(subscriptionTrackingService).processAndCheckSubscription("Netflix", new BigDecimal("15.99"), TX_TIME);
        verifyNoInteractions(budgetAlertRepository, subscriptionAlertRepository, events);
        assertThat(count("centry.expenses.consumed")).isEqualTo(1.0);
        assertThat(count("centry.budget.alerts.emitted")).isZero();
        assertThat(count("centry.subscription.alerts.emitted")).isZero();
        assertThat(registry.timer("centry.expense.processing.duration").count()).isEqualTo(1L);
    }

    @Test
    void nullTimestampDefaultsToNowForEntityAndSubscriptionCheck() {
        LocalDateTime before = LocalDateTime.now();

        service.record(event(null));

        LocalDateTime after = LocalDateTime.now();
        ExpenseEntity e = savedExpense();
        assertThat(e.getTimestamp()).isBetween(before, after);
        ArgumentCaptor<LocalDateTime> passed = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(subscriptionTrackingService).processAndCheckSubscription(eq("Netflix"), any(), passed.capture());
        assertThat(passed.getValue()).isEqualTo(e.getTimestamp());
    }

    @Test
    void okBudgetStatusEmitsNoBudgetAlert() {
        when(budgetTrackingService.recordExpense("Entertainment", new BigDecimal("15.99")))
                .thenReturn(status(BudgetStatus.Status.OK));

        service.record(event(TX_TIME));

        verifyNoInteractions(budgetAlertRepository, events);
        assertThat(count("centry.budget.alerts.emitted")).isZero();
    }

    @Test
    void warningExceededPersistsAndPublishesAlertMentioningWarningThreshold() {
        when(budgetTrackingService.recordExpense("Entertainment", new BigDecimal("15.99")))
                .thenReturn(status(BudgetStatus.Status.WARNING_EXCEEDED));
        LocalDateTime before = LocalDateTime.now();

        service.record(event(TX_TIME));

        String expectedMsg = "Budget alert for [Entertainment]: Current spend $120.00 exceeds warning threshold of $80.00";
        ArgumentCaptor<BudgetAlertEntity> saved = ArgumentCaptor.forClass(BudgetAlertEntity.class);
        verify(budgetAlertRepository).save(saved.capture());
        BudgetAlertEntity a = saved.getValue();
        assertThat(a.getCategory()).isEqualTo("Entertainment");
        assertThat(a.getCurrentSpend()).isEqualByComparingTo("120.00");
        assertThat(a.getMonthlyLimit()).isEqualByComparingTo("100.00");
        assertThat(a.getWarningAmount()).isEqualByComparingTo("80.00");
        assertThat(a.getStatus()).isEqualTo(BudgetStatus.Status.WARNING_EXCEEDED);
        assertThat(a.getMessage()).isEqualTo(expectedMsg);
        assertThat(a.getTimestamp()).isBetween(before, LocalDateTime.now());

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
        when(budgetTrackingService.recordExpense("Entertainment", new BigDecimal("15.99")))
                .thenReturn(status(BudgetStatus.Status.LIMIT_EXCEEDED));

        service.record(event(TX_TIME));

        String expectedMsg = "Budget alert for [Entertainment]: Current spend $120.00 exceeds monthly limit of $100.00";
        ArgumentCaptor<BudgetAlertEntity> saved = ArgumentCaptor.forClass(BudgetAlertEntity.class);
        verify(budgetAlertRepository).save(saved.capture());
        assertThat(saved.getValue().getMessage()).isEqualTo(expectedMsg);
        assertThat(saved.getValue().getStatus()).isEqualTo(BudgetStatus.Status.LIMIT_EXCEEDED);
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
        assertThat(s.getMerchant()).isEqualTo("Netflix");
        assertThat(s.getPreviousAmount()).isEqualByComparingTo("9.99");
        assertThat(s.getCurrentAmount()).isEqualByComparingTo("15.99");
        assertThat(s.getAnomalyType()).isEqualTo("PRICE_INCREASE");
        assertThat(s.getMessage()).isEqualTo("Price went up");
        assertThat(s.getTimestamp()).isEqualTo(TX_TIME);
        verify(events).publishEvent((Object) alert);
        verify(budgetAlertRepository, never()).save(any());
        assertThat(count("centry.subscription.alerts.emitted")).isEqualTo(1.0);
    }

    @Test
    void subscriptionAlertWithoutTimestampGetsCurrentTime() {
        SubscriptionAlertEvent alert = subAlert(null);
        when(subscriptionTrackingService.processAndCheckSubscription("Netflix", new BigDecimal("15.99"), TX_TIME))
                .thenReturn(alert);
        LocalDateTime before = LocalDateTime.now();

        service.record(event(TX_TIME));

        ArgumentCaptor<SubscriptionAlertEntity> saved = ArgumentCaptor.forClass(SubscriptionAlertEntity.class);
        verify(subscriptionAlertRepository).save(saved.capture());
        assertThat(saved.getValue().getTimestamp()).isBetween(before, LocalDateTime.now());
        verify(events).publishEvent((Object) alert);
        assertThat(count("centry.subscription.alerts.emitted")).isEqualTo(1.0);
    }
}
