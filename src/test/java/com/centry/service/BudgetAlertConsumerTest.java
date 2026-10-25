package com.centry.service;

import com.centry.config.BudgetConfigProperties;
import com.centry.model.BudgetAlertEvent;
import com.centry.model.BudgetStatus;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BudgetAlertConsumerTest {

    private record Received(String method, String contentType, String body) {}

    private final ObjectMapper mapper = tools.jackson.databind.json.JsonMapper.builder().build();
    private final List<Received> requests = new CopyOnWriteArrayList<>();
    private HttpServer server;
    private volatile int responseStatus;
    private BudgetConfigProperties config;
    private BudgetAlertConsumer consumer;

    @BeforeEach
    void setUp() throws IOException {
        responseStatus = 200;
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/hook", exchange -> {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            requests.add(new Received(exchange.getRequestMethod(),
                    exchange.getRequestHeaders().getFirst("Content-Type"), body));
            exchange.sendResponseHeaders(responseStatus, -1);
            exchange.close();
        });
        server.start();
        config = new BudgetConfigProperties();
        config.setWebhookUrl("http://127.0.0.1:" + server.getAddress().getPort() + "/hook");
        consumer = new BudgetAlertConsumer(config);
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    private BudgetAlertEvent alert(BudgetStatus.Status level, String message) {
        return new BudgetAlertEvent("food", new BigDecimal("120.00"), new BigDecimal("100.00"),
                new BigDecimal("80.00"), level, message);
    }

    @Test
    void limitExceededPostsCriticalJsonToWebhook() throws Exception {
        consumer.handleBudgetAlert(alert(BudgetStatus.Status.LIMIT_EXCEEDED, "Over budget"));

        assertEquals(1, requests.size());
        Received r = requests.get(0);
        assertEquals("POST", r.method());
        assertTrue(r.contentType().startsWith("application/json"), r.contentType());
        JsonNode json = mapper.readTree(r.body());
        String expected = "🚨 **LIMIT_EXCEEDED**: Over budget";
        assertEquals(expected, json.get("content").asText());
        assertEquals(expected, json.get("text").asText());
    }

    @Test
    void warningPostsWarningPrefixToWebhook() throws Exception {
        consumer.handleBudgetAlert(alert(BudgetStatus.Status.WARNING_EXCEEDED, "Getting close"));

        assertEquals(1, requests.size());
        JsonNode json = mapper.readTree(requests.get(0).body());
        String expected = "⚠️ **WARNING_EXCEEDED**: Getting close";
        assertEquals(expected, json.get("content").asText());
        assertEquals(expected, json.get("text").asText());
    }

    @Test
    void nullWebhookSendsNothing() {
        config.setWebhookUrl(null);
        assertDoesNotThrow(() -> consumer.handleBudgetAlert(alert(BudgetStatus.Status.WARNING_EXCEEDED, "m")));
        assertTrue(requests.isEmpty());
    }

    @Test
    void blankWebhookSendsNothing() {
        config.setWebhookUrl("   ");
        assertDoesNotThrow(() -> consumer.handleBudgetAlert(alert(BudgetStatus.Status.LIMIT_EXCEEDED, "m")));
        assertTrue(requests.isEmpty());
    }

    @Test
    void placeholderWebhookSendsNothing() {
        config.setWebhookUrl("http://127.0.0.1:" + server.getAddress().getPort() + "/YOUR_DISCORD_WEBHOOK");
        assertDoesNotThrow(() -> consumer.handleBudgetAlert(alert(BudgetStatus.Status.LIMIT_EXCEEDED, "m")));
        assertTrue(requests.isEmpty());
    }

    @Test
    void serverErrorIsSwallowed() {
        responseStatus = 500;
        assertDoesNotThrow(() -> consumer.handleBudgetAlert(alert(BudgetStatus.Status.LIMIT_EXCEEDED, "m")));
        assertEquals(1, requests.size());
    }

    @Test
    void unreachableWebhookIsSwallowed() throws IOException {
        int freePort;
        try (ServerSocket s = new ServerSocket(0, 0, InetAddress.getLoopbackAddress())) {
            freePort = s.getLocalPort();
        }
        config.setWebhookUrl("http://127.0.0.1:" + freePort + "/hook");
        assertDoesNotThrow(() -> consumer.handleBudgetAlert(alert(BudgetStatus.Status.WARNING_EXCEEDED, "m")));
        assertTrue(requests.isEmpty());
    }
}
