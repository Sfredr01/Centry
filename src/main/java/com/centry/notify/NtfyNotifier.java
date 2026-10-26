package com.centry.notify;

import org.springframework.http.MediaType;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;

/** Publishes to an ntfy topic: the message is the body, the rest travels in headers. */
public class NtfyNotifier implements Notifier {

    private final RestClient client;
    private final String endpoint;
    private final String token;

    public NtfyNotifier(RestClient.Builder builder, NotifyProperties props) {
        this.client = HttpSupport.client(builder, props);
        this.endpoint = props.ntfy().url().replaceAll("/+$", "") + "/" + props.ntfy().topic();
        this.token = props.ntfy().token();
    }

    @Override
    public void send(Notification notification) {
        RestClient.RequestBodySpec request = client.post()
                .uri(endpoint)
                .contentType(MediaType.TEXT_PLAIN)
                .header("Title", HttpSupport.headerValue(notification.title()))
                .header("Priority", priority(notification.priority()));
        if (!notification.tags().isEmpty()) {
            request.header("Tags", String.join(",", notification.tags()));
        }
        if (StringUtils.hasText(token)) {
            request.header("Authorization", "Bearer " + token);
        }
        HttpSupport.execute(request.body(notification.body()));
    }

    private static String priority(Notification.Priority priority) {
        return switch (priority) {
            case LOW -> "2";
            case DEFAULT -> "3";
            case HIGH -> "5";
        };
    }
}
