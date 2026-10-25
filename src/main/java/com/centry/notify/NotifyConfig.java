package com.centry.notify;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;

import java.util.ArrayList;
import java.util.List;

@Configuration
@EnableConfigurationProperties(NotifyProperties.class)
public class NotifyConfig {

    /** One entry per configured channel; empty when nothing is configured. */
    @Bean
    List<Notifier> notifiers(NotifyProperties props) {
        List<Notifier> notifiers = new ArrayList<>();
        if (StringUtils.hasText(props.ntfy().url()) && StringUtils.hasText(props.ntfy().topic())) {
            notifiers.add(new NtfyNotifier(RestClient.builder(), props));
        }
        if (StringUtils.hasText(props.webhook().url())) {
            notifiers.add(new WebhookNotifier(RestClient.builder(), props));
        }
        return notifiers;
    }
}
