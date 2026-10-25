package com.centry.notify;

import org.springframework.http.client.BufferingClientHttpRequestFactory;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

/** What both notifiers share: a client with timeouts, and "anything but 2xx is a failure". */
final class HttpSupport {

    private HttpSupport() {
    }

    static RestClient client(RestClient.Builder builder, NotifyProperties props) {
        HttpClient http = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(props.connectTimeout())
                .build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(http);
        factory.setReadTimeout(props.readTimeout());
        // The JDK client streams bodies of unknown length as chunked; buffering gives them a Content-Length.
        return builder.requestFactory(new BufferingClientHttpRequestFactory(factory)).build();
    }

    static void execute(RestClient.RequestBodySpec request) {
        try {
            request.retrieve()
                    .onStatus(status -> !status.is2xxSuccessful(), (req, res) -> {
                        throw new NotificationException("Notification endpoint answered " + res.getStatusCode());
                    })
                    .toBodilessEntity();
        } catch (RestClientException e) {
            throw new NotificationException("Notification request failed: " + e.getMessage(), e);
        }
    }

    /** HTTP header values are ASCII; RFC 2047 encoded-words (which ntfy decodes) carry anything else. */
    static String headerValue(String text) {
        if (text.chars().allMatch(c -> c < 0x80)) {
            return text;
        }
        return "=?UTF-8?B?" + Base64.getEncoder().encodeToString(text.getBytes(StandardCharsets.UTF_8)) + "?=";
    }
}
