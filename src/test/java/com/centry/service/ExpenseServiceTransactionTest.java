package com.centry.service;

import com.centry.repository.BudgetAlertRepository;
import com.centry.repository.BudgetRepository;
import com.centry.repository.ExpenseRepository;
import com.centry.repository.SubscriptionAlertRepository;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import com.centry.dto.ExpenseEvent;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;

// A failure after the budget alert was published must undo the expense and keep the webhook silent.
@SpringBootTest
@ActiveProfiles("test")
class ExpenseServiceTransactionTest {

    private static final List<String> webhookBodies = new CopyOnWriteArrayList<>();
    private static HttpServer webhook;

    @BeforeAll
    static void startWebhook() throws IOException {
        webhook = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        webhook.createContext("/hook", exchange -> {
            webhookBodies.add(new String(exchange.getRequestBody().readAllBytes()));
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });
        webhook.start();
    }

    @AfterAll
    static void stopWebhook() {
        webhook.stop(0);
    }

    @DynamicPropertySource
    static void budget(DynamicPropertyRegistry registry) {
        registry.add("centry.notify.webhook.url",
                () -> "http://127.0.0.1:" + webhook.getAddress().getPort() + "/hook");
    }

    @Autowired
    private ExpenseService expenseService;

    @Autowired
    private ExpenseRepository expenseRepository;

    @Autowired
    private BudgetAlertRepository budgetAlertRepository;

    @Autowired
    private BudgetRepository budgetRepository;

    @MockitoSpyBean
    private SubscriptionAlertRepository subscriptionAlertRepository;

    @BeforeEach
    void clean() {
        webhookBodies.clear();
        expenseRepository.deleteAll();
        budgetAlertRepository.deleteAll();
        budgetRepository.upsert("txtest", new BigDecimal("100.00"), 80);
    }

    private static ExpenseEvent event(String tx, String amount) {
        return ExpenseEvent.builder().transactionId(tx).merchant("TxMerchant")
                .amount(new BigDecimal(amount)).category("txtest").build();
    }

    @Test
    void rollsBackOnDownstreamFailure() {
        // First charge only seeds the merchant history, so no subscription alert yet.
        expenseService.record(event("tx-first", "10.00"));
        // Second charge: over the limit (budget alert saved and published) and a price increase,
        // whose alert row then fails to save.
        doThrow(new IllegalStateException("boom")).when(subscriptionAlertRepository).save(any());

        assertThatThrownBy(() -> expenseService.record(event("tx-second", "120.00")))
                .isInstanceOf(IllegalStateException.class).hasMessage("boom");

        assertThat(expenseRepository.findByTransactionId("tx-second")).isEmpty();
        assertThat(expenseRepository.count()).isEqualTo(1);
        assertThat(budgetAlertRepository.findTop50ByOrderByTimestampDesc()).isEmpty();
        await().during(Duration.ofMillis(750)).atMost(Duration.ofSeconds(3))
                .untilAsserted(() -> assertThat(webhookBodies).isEmpty());
    }
}
