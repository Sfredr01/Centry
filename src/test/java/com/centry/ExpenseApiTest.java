package com.centry;

import org.springframework.context.annotation.Import;
import com.centry.entity.ExpenseEntity;
import com.centry.repository.BudgetAlertRepository;
import com.centry.model.Role;
import com.centry.repository.ExpenseRepository;
import com.centry.repository.UserRepository;
import com.jayway.jsonpath.JsonPath;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.test.web.servlet.MvcResult;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// End to end over HTTP in the default profile: no broker, webhook reached in-process.
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(AdminBasicAuth.class)
class ExpenseApiTest {

    private static final List<String> webhookBodies = new CopyOnWriteArrayList<>();
    private static volatile long webhookDelayMillis;
    private static HttpServer webhook;

    @BeforeAll
    static void startWebhook() throws IOException {
        webhook = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        webhook.setExecutor(Executors.newCachedThreadPool());
        webhook.createContext("/hook", exchange -> {
            try {
                Thread.sleep(webhookDelayMillis);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
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
    private MockMvc mockMvc;

    @Autowired
    private ExpenseRepository expenseRepository;

    @Autowired
    private BudgetAlertRepository budgetAlertRepository;

    @Autowired
    private UserRepository users;

    @Autowired
    private JdbcClient jdbc;

    // Alerts are deduplicated per category and month, so every test starts from no spend and no alerts.
    @BeforeEach
    void freshBudgetState() throws Exception {
        expenseRepository.deleteAll();
        budgetAlertRepository.deleteAll();
        mockMvc.perform(put("/api/v1/budgets/apitest").contentType(MediaType.APPLICATION_JSON)
                .content("{\"monthlyLimit\":100.00,\"warnPercent\":80}")).andExpect(status().isOk());
    }

    @Test
    void postPersistsSynchronously() throws Exception {
        expenseRepository.deleteAll();

        mockMvc.perform(post("/api/v1/expenses").contentType(MediaType.APPLICATION_JSON)
                .content("{\"transactionId\":\"tx-sync\",\"merchant\":\"Cafe\",\"amount\":4.50,\"category\":\"misc\"}"))
                .andExpect(status().isCreated());

        List<ExpenseEntity> rows = expenseRepository.findAll();
        assertThat(rows).hasSize(1);
        ExpenseEntity row = rows.get(0);
        assertThat(row.transactionId()).isEqualTo("tx-sync");
        assertThat(row.merchant()).isEqualTo("Cafe");
        assertThat(row.amount()).isEqualByComparingTo("4.50");
        assertThat(row.category()).isEqualTo("misc");
    }

    private long adminId() {
        return users.idByUsername(AdminBasicAuth.USERNAME).orElseThrow();
    }

    @Test
    void apiDefaultsSpentByToCaller() throws Exception {
        mockMvc.perform(post("/api/v1/expenses").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"transactionId\":\"tx-me\",\"merchant\":\"Cafe\",\"amount\":4.50,\"category\":\"misc\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.spentBy").value(adminId()))
                .andExpect(jsonPath("$.createdBy").value(adminId()));
    }

    @Test
    void explicitSpentByIsStoredButCreatorIsAlwaysTheCaller() throws Exception {
        long kid = users.insert("apitest-kid", "Kid", null, Role.MEMBER, LocalDateTime.of(2026, 1, 1, 0, 0)).id();
        try {
            // createdBy in the body is not part of the contract and must not be honoured.
            mockMvc.perform(post("/api/v1/expenses").contentType(MediaType.APPLICATION_JSON)
                            .content("{\"transactionId\":\"tx-kid\",\"merchant\":\"Cafe\",\"amount\":4.50,"
                                    + "\"category\":\"misc\",\"spentBy\":" + kid + ",\"createdBy\":" + kid + "}"))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.spentBy").value(kid))
                    .andExpect(jsonPath("$.createdBy").value(adminId()));
        } finally {
            expenseRepository.deleteAll();
            jdbc.sql("DELETE FROM users WHERE id = ?").param(kid).update();
        }
    }

    @Test
    void unknownSpentBy400() throws Exception {
        mockMvc.perform(post("/api/v1/expenses").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"merchant\":\"Cafe\",\"amount\":4.50,\"category\":\"misc\",\"spentBy\":999999}"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.title").value("Invalid request"))
                .andExpect(jsonPath("$.errors.spentBy").value("no household member with id 999999"));
        assertThat(expenseRepository.count()).isZero();
    }

    @Test
    void created201WithLocationAndBody() throws Exception {
        expenseRepository.deleteAll();

        MvcResult result = mockMvc.perform(post("/api/v1/expenses").contentType(MediaType.APPLICATION_JSON)
                .content("{\"transactionId\":\"tx-201\",\"merchant\":\"Cafe\",\"amount\":4.50,"
                        + "\"category\":\"misc\",\"timestamp\":\"2026-03-04T05:06:07\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.transactionId").value("tx-201"))
                .andExpect(jsonPath("$.merchant").value("Cafe"))
                .andExpect(jsonPath("$.amount").value(4.50))
                .andExpect(jsonPath("$.category").value("misc"))
                .andExpect(jsonPath("$.timestamp").value("2026-03-04T05:06:07"))
                .andReturn();

        long id = expenseRepository.findAll().get(0).id();
        assertThat(result.getResponse().getHeader("Location")).isEqualTo("/api/v1/expenses/" + id);
        assertThat(JsonPath.<Number>read(result.getResponse().getContentAsString(), "$.id").longValue())
                .isEqualTo(id);
    }

    private static final String REPLAYED = "{\"transactionId\":\"tx-replay\",\"merchant\":\"Cafe\","
            + "\"amount\":4.50,\"category\":\"misc\"}";

    @Test
    void replaySameTransactionIdReturns200NoDuplicate() throws Exception {
        expenseRepository.deleteAll();

        MvcResult first = mockMvc.perform(post("/api/v1/expenses").contentType(MediaType.APPLICATION_JSON)
                .content(REPLAYED)).andExpect(status().isCreated()).andReturn();
        MvcResult second = mockMvc.perform(post("/api/v1/expenses").contentType(MediaType.APPLICATION_JSON)
                .content(REPLAYED)).andExpect(status().isOk()).andReturn();

        assertThat(second.getResponse().getHeader("Location")).isEqualTo(first.getResponse().getHeader("Location"));
        assertThat(second.getResponse().getContentAsString()).isEqualTo(first.getResponse().getContentAsString());
        assertThat(expenseRepository.count()).isEqualTo(1);
    }

    @Test
    void conflictingReplayReturns409() throws Exception {
        expenseRepository.deleteAll();
        mockMvc.perform(post("/api/v1/expenses").contentType(MediaType.APPLICATION_JSON).content(REPLAYED))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/api/v1/expenses").contentType(MediaType.APPLICATION_JSON)
                .content(REPLAYED.replace("4.50", "9.99")))
                .andExpect(status().isConflict())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.detail").value("transactionId already used for a different expense"));

        assertThat(expenseRepository.count()).isEqualTo(1);
    }

    @Test
    void getById200() throws Exception {
        expenseRepository.deleteAll();
        ExpenseEntity saved = expenseRepository.save(new ExpenseEntity(null, "tx-get", "Cafe",
                new BigDecimal("4.50"), "misc", LocalDateTime.of(2026, 3, 4, 5, 6, 7), null, null));

        mockMvc.perform(get("/api/v1/expenses/" + saved.id()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(saved.id()))
                .andExpect(jsonPath("$.transactionId").value("tx-get"))
                .andExpect(jsonPath("$.amount").value(4.50));
    }

    @Test
    void getUnknownId404() throws Exception {
        mockMvc.perform(get("/api/v1/expenses/987654"))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(404));
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource(delimiter = '|', value = {
            "blank merchant|{\"merchant\":\" \",\"amount\":4.50,\"category\":\"misc\"}|merchant",
            "null amount|{\"merchant\":\"Cafe\",\"category\":\"misc\"}|amount",
            "negative amount|{\"merchant\":\"Cafe\",\"amount\":-1.00,\"category\":\"misc\"}|amount",
            "sub-cent amount|{\"merchant\":\"Cafe\",\"amount\":4.505,\"category\":\"misc\"}|amount",
            "blank category|{\"merchant\":\"Cafe\",\"amount\":4.50,\"category\":\"\"}|category"})
    void invalidPayload400WithFieldErrors(String name, String json, String field) throws Exception {
        expenseRepository.deleteAll();

        mockMvc.perform(post("/api/v1/expenses").contentType(MediaType.APPLICATION_JSON).content(json))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.title").value("Invalid request"))
                .andExpect(jsonPath("$.errors." + field).isString());

        assertThat(expenseRepository.count()).isZero();
    }

    @Test
    void malformedJson400() throws Exception {
        mockMvc.perform(post("/api/v1/expenses").contentType(MediaType.APPLICATION_JSON).content("{not json"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.title").value("Invalid request"));
    }

    @Test
    void budgetAlertReachesWebhookWithoutKafka() throws Exception {
        webhookBodies.clear();
        webhookDelayMillis = 0;

        mockMvc.perform(post("/api/v1/expenses").contentType(MediaType.APPLICATION_JSON)
                .content("{\"merchant\":\"Store\",\"amount\":150.00,\"category\":\"apitest\"}"))
                .andExpect(status().isCreated());

        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> assertThat(webhookBodies).hasSize(1));
        assertThat(webhookBodies.get(0)).contains("🚨 **Centry budget alert: apitest**");
    }

    @Test
    void secondExpenseOverLimitDoesNotRealert() throws Exception {
        webhookBodies.clear();
        webhookDelayMillis = 0;
        String body = "{\"merchant\":\"Store\",\"amount\":%s,\"category\":\"apitest\"}";

        mockMvc.perform(post("/api/v1/expenses").contentType(MediaType.APPLICATION_JSON)
                .content(body.formatted("150.00"))).andExpect(status().isCreated());
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> assertThat(webhookBodies).hasSize(1));
        mockMvc.perform(post("/api/v1/expenses").contentType(MediaType.APPLICATION_JSON)
                .content(body.formatted("10.00"))).andExpect(status().isCreated());

        await().during(Duration.ofMillis(750)).atMost(Duration.ofSeconds(3))
                .untilAsserted(() -> assertThat(webhookBodies).hasSize(1));
    }

    // Kafka used to decouple the webhook from the request; in-process it must stay off the request thread.
    @Test
    void postDoesNotWaitForSlowWebhook() throws Exception {
        webhookBodies.clear();
        webhookDelayMillis = 3000;

        long start = System.nanoTime();
        mockMvc.perform(post("/api/v1/expenses").contentType(MediaType.APPLICATION_JSON)
                .content("{\"merchant\":\"Store\",\"amount\":150.00,\"category\":\"apitest\"}"))
                .andExpect(status().isCreated());
        Duration elapsed = Duration.ofNanos(System.nanoTime() - start);

        assertThat(elapsed).isLessThan(Duration.ofMillis(1500));
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> assertThat(webhookBodies).hasSize(1));
    }
}
