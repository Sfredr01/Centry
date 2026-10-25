package com.centry.notify;

import com.centry.model.BudgetAlertEvent;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.support.StaticListableBeanFactory;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/** Records the alerts it is asked to send; can be told to fail. */
class FakeNotifier implements Notifier {

    private final List<BudgetAlertEvent> sent = new CopyOnWriteArrayList<>();
    private volatile boolean failing;

    /** What Spring would inject for the given notifier beans. */
    static ObjectProvider<Notifier> providerOf(Notifier... notifiers) {
        StaticListableBeanFactory beans = new StaticListableBeanFactory();
        for (int i = 0; i < notifiers.length; i++) {
            beans.addBean("notifier" + i, notifiers[i]);
        }
        return beans.getBeanProvider(Notifier.class);
    }

    @Override
    public void send(BudgetAlertEvent alert) {
        sent.add(alert);
        if (failing) {
            throw new NotificationException("boom");
        }
    }

    List<BudgetAlertEvent> sent() {
        return sent;
    }

    void failing(boolean failing) {
        this.failing = failing;
    }

    void reset() {
        sent.clear();
        failing = false;
    }
}
