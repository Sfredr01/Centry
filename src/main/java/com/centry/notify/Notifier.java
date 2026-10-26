package com.centry.notify;

/** One outbound channel for notifications. */
public interface Notifier {

    /** Delivers the notification; throws {@link NotificationException} on an I/O error or a non-2xx answer. */
    void send(Notification notification);
}
