package com.centry.notify;

import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

/** A notifier bean exists only when its channel is configured; blank values count as not configured. */
@Configuration
@EnableConfigurationProperties(NotifyProperties.class)
public class NotifyConfig {

    @Bean
    @ConditionalOnExpression("!'${centry.notify.ntfy.url:}'.isBlank() && !'${centry.notify.ntfy.topic:}'.isBlank()")
    NtfyNotifier ntfyNotifier(NotifyProperties props) {
        return new NtfyNotifier(RestClient.builder(), props);
    }

    @Bean
    @ConditionalOnExpression("!'${centry.notify.webhook.url:}'.isBlank()")
    WebhookNotifier webhookNotifier(NotifyProperties props) {
        return new WebhookNotifier(RestClient.builder(), props);
    }
}
