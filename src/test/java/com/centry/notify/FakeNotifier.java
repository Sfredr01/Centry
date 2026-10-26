package com.centry.notify;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.support.StaticListableBeanFactory;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/** Records the notifications it is asked to send; can be told to fail. */
class FakeNotifier implements Notifier {

    private final List<Notification> sent = new CopyOnWriteArrayList<>();
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
    public void send(Notification notification) {
        sent.add(notification);
        if (failing) {
            throw new NotificationException("boom");
        }
    }

    List<Notification> sent() {
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
