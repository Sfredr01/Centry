package com.centry.notify;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/**
 * Outbound notification settings. A channel exists only when its url (and, for ntfy, topic) is set.
 * The timeouts bound every notifier call so a hung endpoint cannot pin a thread.
 */
@ConfigurationProperties("centry.notify")
public record NotifyProperties(
        @DefaultValue Ntfy ntfy,
        @DefaultValue Webhook webhook,
        @DefaultValue("PT5M") Duration retryInterval,
        @DefaultValue("PT5S") Duration connectTimeout,
        @DefaultValue("PT10S") Duration readTimeout) {

    public record Ntfy(String url, String topic, String token) {
    }

    public record Webhook(String url) {
    }
}
