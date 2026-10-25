package com.centry.notify;

import com.centry.model.BudgetAlertEvent;
import com.centry.model.BudgetLevel;
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
    public void send(BudgetAlertEvent alert) {
        boolean limit = alert.getAlertLevel() == BudgetLevel.LIMIT_EXCEEDED;
        RestClient.RequestBodySpec request = client.post()
                .uri(endpoint)
                .contentType(MediaType.TEXT_PLAIN)
                .header("Title", HttpSupport.headerValue("Centry budget alert: " + alert.getCategory()))
                .header("Priority", limit ? "5" : "3")
                .header("Tags", limit ? "rotating_light" : "warning");
        if (StringUtils.hasText(token)) {
            request.header("Authorization", "Bearer " + token);
        }
        HttpSupport.execute(request.body(alert.getMessage()));
    }
}
