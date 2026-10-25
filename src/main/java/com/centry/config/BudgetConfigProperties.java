package com.centry.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** Budgets themselves live in the database (PUT /api/v1/budgets/{category}); only the webhook is configured. */
@Configuration
@ConfigurationProperties(prefix = "centry.budget")
public class BudgetConfigProperties {

    private String webhookUrl;

    public String getWebhookUrl() { return webhookUrl; }

    public void setWebhookUrl(String webhookUrl) { this.webhookUrl = webhookUrl; }
}
