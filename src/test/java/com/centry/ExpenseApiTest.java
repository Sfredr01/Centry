package com.centry;

import com.centry.entity.ExpenseEntity;
import com.centry.repository.ExpenseRepository;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// End to end over HTTP in the default profile: no broker, webhook reached in-process.
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ExpenseApiTest {

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
        registry.add("centry.budget.webhook-url",
                () -> "http://127.0.0.1:" + webhook.getAddress().getPort() + "/hook");
        registry.add("centry.budget.categories.apitest.monthly-limit", () -> "100.00");
        registry.add("centry.budget.categories.apitest.warning-threshold-percent", () -> "0.80");
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ExpenseRepository expenseRepository;

    @Test
    void postPersistsSynchronously() throws Exception {
        expenseRepository.deleteAll();

        mockMvc.perform(post("/api/v1/expenses").contentType(MediaType.APPLICATION_JSON)
                .content("{\"transactionId\":\"tx-sync\",\"merchant\":\"Cafe\",\"amount\":4.50,\"category\":\"misc\"}"))
                .andExpect(status().isOk());

        List<ExpenseEntity> rows = expenseRepository.findAll();
        assertThat(rows).hasSize(1);
        ExpenseEntity row = rows.get(0);
        assertThat(row.getTransactionId()).isEqualTo("tx-sync");
        assertThat(row.getMerchant()).isEqualTo("Cafe");
        assertThat(row.getAmount()).isEqualByComparingTo("4.50");
        assertThat(row.getCategory()).isEqualTo("misc");
    }

    @Test
    void budgetAlertReachesWebhookWithoutKafka() throws Exception {
        webhookBodies.clear();

        mockMvc.perform(post("/api/v1/expenses").contentType(MediaType.APPLICATION_JSON)
                .content("{\"merchant\":\"Store\",\"amount\":150.00,\"category\":\"apitest\"}"))
                .andExpect(status().isOk());

        assertThat(webhookBodies).hasSize(1);
        assertThat(webhookBodies.get(0)).contains("LIMIT_EXCEEDED");
    }
}
