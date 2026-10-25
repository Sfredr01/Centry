package com.centry.notify;

import com.centry.model.BudgetAlertEvent;
import com.centry.model.BudgetLevel;
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
    public void send(BudgetAlertEvent alert) {
        String text = String.format("%s **%s**: %s",
                alert.getAlertLevel() == BudgetLevel.LIMIT_EXCEEDED ? "🚨" : "⚠️",
                alert.getAlertLevel(), alert.getMessage());
        HttpSupport.execute(client.post()
                .uri(url)
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("content", text, "text", text)));
    }
}
