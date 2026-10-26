package com.centry.notify;

import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;

import java.util.Map;

/** Posts a payload both Discord ("content") and Slack ("text") understand. */
public class WebhookNotifier implements Notifier {

    private final RestClient client;
    private final String url;

    public WebhookNotifier(RestClient.Builder builder, NotifyProperties props) {
        this.client = HttpSupport.client(builder, props);
        this.url = props.webhook().url();
    }

    @Override
    public void send(Notification notification) {
        String text = String.format("%s **%s**: %s", emoji(notification.priority()), notification.title(),
                notification.body());
        HttpSupport.execute(client.post()
                .uri(url)
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("content", text, "text", text)));
    }

    private static String emoji(Notification.Priority priority) {
        return switch (priority) {
            case LOW -> "ℹ️";
            case DEFAULT -> "⚠️";
            case HIGH -> "🚨";
        };
    }
}
