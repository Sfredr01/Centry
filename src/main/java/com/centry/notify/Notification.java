package com.centry.notify;

import java.util.List;

/** What a {@link Notifier} delivers: channel-neutral text plus a priority and ntfy-style emoji tags. */
public record Notification(String title, String body, Priority priority, List<String> tags) {

    public enum Priority {
        LOW, DEFAULT, HIGH
    }
}
