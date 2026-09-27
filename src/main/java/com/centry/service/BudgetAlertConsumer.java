package com.centry.service;

import com.centry.config.BudgetConfigProperties;
import com.centry.config.KafkaTopicConfig;
import com.centry.model.BudgetAlertEvent;
import com.centry.model.BudgetStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.util.Map;

@Service
public class BudgetAlertConsumer {

    private static final Logger log = LoggerFactory.getLogger(BudgetAlertConsumer.class);

    private final BudgetConfigProperties budgetConfig;
    private final RestClient restClient;

    public BudgetAlertConsumer(BudgetConfigProperties budgetConfig) {
        this.budgetConfig = budgetConfig;
        this.restClient = RestClient.create();
    }

    @KafkaListener(topics = KafkaTopicConfig.BUDGET_ALERTS_TOPIC, groupId = "centry-alert-group")
    public void handleBudgetAlert(BudgetAlertEvent alert) {
        if (alert.getAlertLevel() == BudgetStatus.Status.LIMIT_EXCEEDED) {
            log.error("🚨 CRITICAL BUDGET ALERT: Category [{}] - Total spend: ${} (Limit: ${}). Message: {}",
                    alert.getCategory(), alert.getCurrentSpend(), alert.getMonthlyLimit(), alert.getMessage());
        } else {
            log.warn("⚠️ WARNING BUDGET ALERT: Category [{}] - Total spend: ${} (Threshold: ${}). Message: {}",
                    alert.getCategory(), alert.getCurrentSpend(), alert.getWarningThreshold(), alert.getMessage());
        }

        sendExternalNotification(alert);
    }

    private void sendExternalNotification(BudgetAlertEvent alert) {
        String webhookUrl = budgetConfig.getWebhookUrl();

        if (webhookUrl == null || webhookUrl.isBlank() || webhookUrl.contains("YOUR_DISCORD")) {
            log.debug("Webhook URL not configured. Skipping external notification.");
            return;
        }

        try {
            // Discord & Slack both support simple JSON payload with a "content" or "text" field
            String formattedMessage = String.format("%s **%s**: %s",
                    alert.getAlertLevel() == BudgetStatus.Status.LIMIT_EXCEEDED ? "🚨" : "⚠️",
                    alert.getAlertLevel(),
                    alert.getMessage());

            Map<String, String> payload = Map.of(
                    "content", formattedMessage, // Discord field name
                    "text", formattedMessage     // Slack field name
            );

            restClient.post()
                    .uri(webhookUrl)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(payload)
                    .retrieve()
                    .toBodilessEntity();

            log.info("Successfully dispatched budget alert webhook for category [{}]", alert.getCategory());
        } catch (Exception e) {
            log.error("Failed to send webhook notification for alert on category [{}]: {}", 
                    alert.getCategory(), e.getMessage());
        }
    }
}