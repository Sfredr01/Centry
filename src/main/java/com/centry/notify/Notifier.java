package com.centry.notify;

import com.centry.model.BudgetAlertEvent;

/** One outbound channel for budget alerts. */
public interface Notifier {

    /** Delivers the alert; throws {@link NotificationException} on an I/O error or a non-2xx answer. */
    void send(BudgetAlertEvent alert);
}
