package com.centry.notify;

import com.centry.model.BudgetAlertEvent;
import com.centry.model.BudgetLevel;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class NtfyNotifierTest {

    private RecordingServer server;

    @BeforeEach
    void start() throws IOException {
        server = new RecordingServer();
    }

    @AfterEach
    void stop() {
        server.close();
    }

    private NtfyNotifier notifier(String token) {
        return new NtfyNotifier(RestClient.builder(), new NotifyProperties(
                new NotifyProperties.Ntfy(server.url(), "centry-alerts", token), new NotifyProperties.Webhook(null),
                Duration.ofMinutes(5), Duration.ofSeconds(5), Duration.ofSeconds(10)));
    }

    private static BudgetAlertEvent alert(String category, BudgetLevel level) {
        return new BudgetAlertEvent(category, new BigDecimal("120.00"), new BigDecimal("100.00"),
                new BigDecimal("80.00"), level, "Over budget");
    }

    @Test
    void postsMessageWithTitlePriorityAndTags() {
        notifier(null).send(alert("food", BudgetLevel.WARNING_EXCEEDED));

        assertThat(server.requests()).singleElement().satisfies(r -> {
            assertThat(r.method()).isEqualTo("POST");
            assertThat(r.path()).isEqualTo("/centry-alerts");
            assertThat(r.body()).isEqualTo("Over budget");
            assertThat(r.header("Title")).isEqualTo("Centry budget alert: food");
            assertThat(r.header("Priority")).isEqualTo("3");
            assertThat(r.header("Tags")).isEqualTo("warning");
        });
    }

    @Test
    void limitIsPriority5WarningIs3() {
        notifier(null).send(alert("food", BudgetLevel.LIMIT_EXCEEDED));

        assertThat(server.requests()).singleElement().satisfies(r -> {
            assertThat(r.header("Priority")).isEqualTo("5");
            assertThat(r.header("Tags")).isEqualTo("rotating_light");
        });
    }

    @Test
    void sendsBearerTokenOnlyWhenConfigured() {
        notifier(null).send(alert("food", BudgetLevel.LIMIT_EXCEEDED));
        notifier("").send(alert("food", BudgetLevel.LIMIT_EXCEEDED));
        notifier("tk_secret").send(alert("food", BudgetLevel.LIMIT_EXCEEDED));

        assertThat(server.requests()).extracting(r -> r.header("Authorization"))
                .containsExactly(null, null, "Bearer tk_secret");
    }

    @Test
    void nonAsciiCategoryIsRfc2047EncodedInTheTitle() {
        notifier(null).send(alert("Café 🚗", BudgetLevel.WARNING_EXCEEDED));

        assertThat(server.requests().get(0).header("Title"))
                .isEqualTo("=?UTF-8?B?Q2VudHJ5IGJ1ZGdldCBhbGVydDogQ2Fmw6kg8J+alw==?=");
    }

    @Test
    void non2xxThrows() {
        server.respondWith(500);

        assertThatThrownBy(() -> notifier(null).send(alert("food", BudgetLevel.LIMIT_EXCEEDED)))
                .isInstanceOf(NotificationException.class).hasMessageContaining("500");
    }

    @Test
    void redirectCountsAsFailure() {
        server.respondWith(302);

        assertThatThrownBy(() -> notifier(null).send(alert("food", BudgetLevel.LIMIT_EXCEEDED)))
                .isInstanceOf(NotificationException.class).hasMessageContaining("302");
    }
}
