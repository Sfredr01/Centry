package com.centry.notify;

import com.centry.model.BudgetAlertEvent;
import com.centry.model.BudgetLevel;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.time.Duration;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WebhookNotifierTest {

    private final ObjectMapper mapper = JsonMapper.builder().build();
    private RecordingServer server;

    @BeforeEach
    void start() throws IOException {
        server = new RecordingServer();
    }

    @AfterEach
    void stop() {
        server.close();
    }

    private WebhookNotifier notifier(String url, Duration connectTimeout, Duration readTimeout) {
        return new WebhookNotifier(RestClient.builder(), new NotifyProperties(
                new NotifyProperties.Ntfy(null, null, null), new NotifyProperties.Webhook(url),
                Duration.ofMinutes(5), connectTimeout, readTimeout));
    }

    private WebhookNotifier notifier() {
        return notifier(server.url() + "/hook", Duration.ofSeconds(5), Duration.ofSeconds(10));
    }

    private static Notification alert(BudgetLevel level, String message) {
        return AlertDispatcher.toNotification(new BudgetAlertEvent("food", new BigDecimal("120.00"),
                new BigDecimal("100.00"), new BigDecimal("80.00"), level, message));
    }

    @Test
    void discordSlackPayload() throws Exception {
        notifier().send(alert(BudgetLevel.LIMIT_EXCEEDED, "Over budget"));
        notifier().send(alert(BudgetLevel.WARNING_EXCEEDED, "Getting close"));

        assertThat(server.requests()).hasSize(2);
        var limit = server.requests().get(0);
        assertThat(limit.method()).isEqualTo("POST");
        assertThat(limit.path()).isEqualTo("/hook");
        assertThat(limit.header("Content-Type")).startsWith("application/json");
        JsonNode json = mapper.readTree(limit.body());
        assertThat(json.get("content").asText()).isEqualTo("🚨 **Centry budget alert: food**: Over budget");
        assertThat(json.get("text").asText()).isEqualTo("🚨 **Centry budget alert: food**: Over budget");
        assertThat(mapper.readTree(server.requests().get(1).body()).get("content").asText())
                .isEqualTo("⚠️ **Centry budget alert: food**: Getting close");
    }

    @Test
    void lowPriorityUsesInfoEmoji() throws Exception {
        notifier().send(new Notification("Summary", "All quiet", Notification.Priority.LOW, java.util.List.of()));

        assertThat(mapper.readTree(server.requests().get(0).body()).get("text").asText())
                .isEqualTo("ℹ️ **Summary**: All quiet");
    }

    // Some webhook endpoints reject chunked uploads, so the body must go out with a Content-Length.
    @Test
    void sendsContentLengthNotChunked() {
        notifier().send(alert(BudgetLevel.LIMIT_EXCEEDED, "Over budget"));

        var request = server.requests().get(0);
        assertThat(request.header("Content-Length")).isEqualTo(String.valueOf(request.body().getBytes(UTF_8).length));
        assertThat(request.header("Transfer-Encoding")).isNull();
    }

    @Test
    void non2xxThrows() {
        server.respondWith(500);

        assertThatThrownBy(() -> notifier().send(alert(BudgetLevel.LIMIT_EXCEEDED, "m")))
                .isInstanceOf(NotificationException.class).hasMessageContaining("500");
        assertThat(server.requests()).hasSize(1);
    }

    @Test
    void unreachableServerThrows() throws IOException {
        int freePort;
        try (ServerSocket s = new ServerSocket(0, 0, InetAddress.getLoopbackAddress())) {
            freePort = s.getLocalPort();
        }

        assertThatThrownBy(() -> notifier("http://127.0.0.1:" + freePort + "/hook", Duration.ofSeconds(5),
                Duration.ofSeconds(10)).send(alert(BudgetLevel.WARNING_EXCEEDED, "m")))
                .isInstanceOf(NotificationException.class);
    }

    @Test
    void timesOutOnHungServer() {
        server.hang();
        WebhookNotifier hung = notifier(server.url() + "/hook", Duration.ofSeconds(1), Duration.ofSeconds(1));

        long start = System.nanoTime();
        assertThatThrownBy(() -> hung.send(alert(BudgetLevel.LIMIT_EXCEEDED, "m")))
                .isInstanceOf(NotificationException.class);

        assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(15));
    }
}
